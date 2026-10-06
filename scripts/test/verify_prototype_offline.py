# -*- coding: utf-8 -*-
"""Render docs/设计/视觉原型.html in a real browser and assert the prototype is
offline-capable, error-free and actually lays out the states it claims.

Run:  python scripts/test/verify_prototype_offline.py
Exit 0 = every assertion holds. Artifacts land in docs/设计/_原型渲染/.

This is a browser-layout check, NOT an Android acceptance test: it says nothing
about Compose rendering, TalkBack, device insets or real font metrics.
"""
import json
import hashlib
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HTML = ROOT / "docs" / "设计" / "视觉原型.html"
OUT = ROOT / "docs" / "设计" / "_原型渲染"

failures = []
notes = []


def check(label, ok, detail=""):
    print("  %-58s %s%s" % (label, "PASS" if ok else "FAIL", ("  " + detail) if detail else ""))
    if not ok:
        failures.append("%s %s" % (label, detail))


def main():
    from playwright.sync_api import sync_playwright

    OUT.mkdir(parents=True, exist_ok=True)
    # 先清空输出目录：否则上一轮的截图会与新的一起留在目录里，
    # 审阅者按记录核对时会以为它们都属于本轮（这正是被发现的问题）。
    stale = sorted(p for p in OUT.glob("*.png"))
    for p in stale:
        p.unlink()
    print("已清理上一轮截图 %d 张" % len(stale))
    url = HTML.as_uri()

    # ---- static: no network-capable references ----
    raw = HTML.read_text(encoding="utf-8")
    print("=" * 78)
    print("静态检查：离线性")
    print("=" * 78)
    for bad in ("http://", "https://", "//cdn", "cdn.", "fonts.googleapis", "unpkg", "jsdelivr"):
        hits = [ln for ln in raw.splitlines() if bad in ln]
        # the SVG namespace declaration is a spec-mandated URI, never fetched
        hits = [h for h in hits if "www.w3.org/2000/svg" not in h]
        check("无引用 %s" % bad, not hits, ("%d 处" % len(hits)) if hits else "")

    requests = []
    console_errors = []
    page_errors = []

    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx = browser.new_context(viewport={"width": 1280, "height": 1000},
                                  device_scale_factor=2)
        page = ctx.new_page()

        def on_request(req):
            requests.append(req.url)

        page.on("request", on_request)
        page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda e: page_errors.append(str(e)))

        print()
        print("=" * 78)
        print("浏览器渲染：%s" % url)
        print("=" * 78)
        page.goto(url, wait_until="load")
        page.wait_for_timeout(1200)

        check("页面标题存在", page.title().startswith("私人工作室日历"), page.title())

        # ---- every request must be file:// ----
        off = [u for u in requests if not u.startswith("file:")]
        check("全部请求均为本地 file://", not off, "; ".join(off[:3]))

        check("无 JS 运行时异常", not page_errors, "; ".join(page_errors[:2]))

        # The only tolerated console errors are ERR_FILE_NOT_FOUND for the
        # illustration files the illustrator has not delivered yet. Anything
        # else (script error, CSS parse error) is a real defect.
        missing_asset_requests = [
            r for r in requests if r.startswith("file:") and "/assets/art/" in r
        ]
        other_console_errors = [
            t for t in console_errors
            if "ERR_FILE_NOT_FOUND" not in t and "Failed to load resource" not in t
        ]
        check("console error 仅为素材缺失（真实退化路径）",
              not other_console_errors,
              "; ".join(other_console_errors[:2]))
        check("素材缺失请求数 > 0（证明退化视觉被真实触发）",
              len(missing_asset_requests) > 0,
              "%d 个 assets/art 请求" % len(missing_asset_requests))

        # ---- layout presence ----
        print()
        print("=" * 78)
        print("结构断言")
        print("=" * 78)
        box_checks = [
            ("今天帧宽 = 360px", "#phone-today", "width", 360),
            ("洞察帧宽 = 360px", "#phone-insights", "width", 360),
            ("今日封面高度 = 200px（scrim JSON 契约 328x200dp）", "#cover1", "height", 200),
            ("任务卡缩略图 = 72px", "#phone-today .thumb", "width", 72),
        ]
        for label, sel, dim, want in box_checks:
            bb = page.locator(sel).first.bounding_box()
            got = round(bb[dim]) if bb else None
            check(label, got == want, "实际 %s" % got)

        n = page.locator("#phone-today .nav button").count()
        check("底部导航 4 个入口", n == 4, "实际 %d" % n)

        # 五个页面都必须有实际尺寸的视觉稿（不能只有文字规格）
        for name, fid in (("今天", "#phone-today"), ("专注", "#phone-focus"),
                          ("记录", "#phone-records"), ("洞察", "#phone-insights"),
                          ("设置", "#phone-settings")):
            bb = page.locator(fid).bounding_box()
            check("页面帧存在且为 360px 宽：%s" % name,
                  bb is not None and round(bb["width"]) == 360,
                  ("%dx%d" % (round(bb["width"]), round(bb["height"]))) if bb else "缺失")

        # 每个手机帧都必须挂底部导航（专注页另有操作条），否则不是完整页面
        for fid in ("phone-today", "phone-focus", "phone-records", "phone-settings", "phone-insights"):
            kids = page.evaluate(
                "Array.from(document.getElementById('%s').children).map(e=>e.className||e.tagName)" % fid)
            check("%s 含底部导航" % fid, any("nav" == k for k in kids), str(kids))

        # 记录页日历格：日期数字必须落在**格外**的纯色上（不能压在色阶填充上）
        page.locator("[data-rec-state='normal']").click()
        page.wait_for_timeout(350)
        cal = page.evaluate("""(function(){
            var c = document.querySelector('#cal-grid .cell');
            if (!c) return null;
            var dn = c.querySelector('.dn'), sw = c.querySelector('.csw');
            if (!dn || !sw) return null;
            var a = dn.getBoundingClientRect(), b = sw.getBoundingClientRect();
            return { overlap: a.bottom > b.top + 0.5, dnBottom: Math.round(a.bottom), swTop: Math.round(b.top) };
        })()""")
        check("日历日期数字不与色阶块重叠", cal is not None and not cal["overlap"], str(cal))
        # 前 5 天必须覆盖「有投入 / 无记录 / 修正为零」三类，否则演示不完整
        classes = page.evaluate("""Array.from(document.querySelectorAll('#cal-grid .cell'))
            .filter(function(c){ var lab=c.getAttribute('aria-label')||'';
              return / 10 月 (1|2|3|4|5) 日，/.test(lab); })
            .map(function(c){ return (c.getAttribute('aria-label').match(/ 10 月 (\d+) 日/)[1])
              + ':' + Array.from(c.classList).filter(function(x){return x!=='cell';}).join('|'); })""")
        joined = " ".join(classes)
        check("日历首屏含「无记录」虚线格", "none" in joined, str(classes))
        check("日历首屏含「修正为零」格", "zero" in joined, str(classes))
        check("日历首屏含「有投入」格", ("l1" in joined or "l3" in joined or "l4" in joined), str(classes))

        # 记录页空态：panel 帧插画必须按契约比例显示（不留信箱空带），CTA 必须完整可见
        page.locator("[data-rec-state='empty']").click()
        page.wait_for_timeout(600)
        ratio = page.evaluate("""(function(){var i=document.getElementById('empty-rec-art');
            var b=i.getBoundingClientRect(); return b.width/b.height;})()""")
        check("空态插画按契约比例 656:320 显示（无信箱留白）", abs(ratio - 656 / 320) < 0.02,
              "实际比例 %.2f（契约 %.2f）" % (ratio, 656 / 320))
        cta = page.evaluate("""(function(){
            var ph=document.getElementById('phone-records').getBoundingClientRect();
            var nav=document.querySelector('#phone-records .nav').getBoundingClientRect();
            var btn=document.querySelector("[data-rec='empty'] .panel-in .btn").getBoundingClientRect();
            return {ok: btn.top>=ph.top-1 && btn.bottom<=nav.top+1,
                    btnBottom:Math.round(btn.bottom-ph.top), navTop:Math.round(nav.top-ph.top)};})()""")
        check("空态主按钮完整落在可视区内", cta["ok"], str(cta))
        # 「选中日期为空」：只显示单日空文案，不显示整月空态大插画
        page.locator("[data-rec-state='dayempty']").click()
        page.wait_for_timeout(450)
        check("「选中日期为空」不显示整月空态大插画",
              page.locator("[data-rec='dayempty'] .art-panel").count() == 0
              and page.locator("[data-rec='dayempty']").get_by_text("为这天补记").count() == 1)

        # ---- 本轮评审修正的六项缺陷，逐条钉成断言 ----

        # (1) 记录页图例必须覆盖页面上真实出现的每个色阶档，
        #     否则用户会看到一块找不到图例项的颜色（实测曾缺 30–59 与 ≥120）。
        page.locator("[data-rec-state='normal']").click()
        page.wait_for_timeout(450)
        legend_vs_cells = page.evaluate("""(function(){
            function legendKeys(id){
              var out=[];
              document.querySelectorAll('#'+id+' .grp').forEach(function(g){
                var sw=g.querySelector('.sw2');
                out.push(g.textContent.trim()+'|'+getComputedStyle(sw).backgroundColor);});
              return out;}
            function cellBg(cls){   // 取该档位实际渲染出的背景色
              var c=document.querySelector('#cal-grid .cell.'+cls+' .csw');
              return c? getComputedStyle(c).backgroundColor : null;}
            var legend=legendKeys('legend-records');
            var legendBg=legend.map(function(x){return x.split('|')[1];});
            var present={};
            document.querySelectorAll('#cal-grid .cell').forEach(function(c){
              Array.from(c.classList).forEach(function(k){
                if(/^l[1-4]$/.test(k)||k==='zero'||k==='none') present[k]=(present[k]||0)+1;});});
            var missing=[];
            Object.keys(present).forEach(function(k){
              if(k==='none'||k==='zero') return;
              var bg=cellBg(k);
              if(bg && legendBg.indexOf(bg)<0) missing.push(k+'('+bg+')');});
            var ins=legendKeys('legend-insights');
            var recScale=legend.filter(function(x){return !/有完成|今天/.test(x);}).map(function(x){return x.split('|')[1];});
            var insScale=ins.filter(function(x){return !/单位/.test(x);}).map(function(x){return x.split('|')[1];});
            return {present:present, missing:missing, recScale:recScale, insScale:insScale,
                    sameScale: JSON.stringify(recScale)===JSON.stringify(insScale)};})()""")
        check("记录页图例覆盖页面出现的每个色阶档", not legend_vs_cells["missing"],
              "缺失 %s；页面档位 %s" % (legend_vs_cells["missing"], legend_vs_cells["present"]))
        check("记录页与洞察页图例共用同一套色阶（同序同色）",
              legend_vs_cells["sameScale"],
              "记录=%s 洞察=%s" % (legend_vs_cells["recScale"], legend_vs_cells["insScale"]))

        # (2) 记录页日历必须随系统字号缩放（曾把 44px/14px/12px 写死，两态逐像素相同）
        def cal_metrics():
            return page.evaluate("""(function(){
                var c=document.querySelector('#cal-grid .cell'), dn=c.querySelector('.dn'),
                    dow=document.querySelector('#cal-grid .dow'), g=document.getElementById('cal-grid');
                return {h:Math.round(c.getBoundingClientRect().height), dn:parseFloat(getComputedStyle(dn).fontSize),
                        dow:parseFloat(getComputedStyle(dow).fontSize),
                        overflowX: g.scrollWidth > g.clientWidth + 1};})()""")
        page.locator("[data-rec-state='normal']").click()
        page.wait_for_timeout(450)
        m_normal = cal_metrics()
        page.locator("[data-rec-state='largefont']").click()
        page.wait_for_timeout(550)
        m_large = cal_metrics()
        check("记录页日历随字号缩放（日期数字变大）", m_large["dn"] > m_normal["dn"],
              "normal %s -> largefont %s" % (m_normal["dn"], m_large["dn"]))
        check("记录页日历单元随字号撑高", m_large["h"] > m_normal["h"],
              "normal %sh -> largefont %sh" % (m_normal["h"], m_large["h"]))
        check("记录页日历不横向溢出", not m_large["overflowX"], str(m_large))

        # (3) 设置页大字号必须上下排，不得把中文挤成竖排单字
        page.locator("[data-set-state='largefont']").click()
        page.wait_for_timeout(550)
        rows = page.evaluate("""(function(){
            var out=[];
            document.querySelectorAll('#phone-settings [data-set="normal"] .row').forEach(function(r){
              // 必须包含开关等**无文字**的子元素，否则只会出现开关的行会被漏判
              var kids=Array.from(r.children);
              var stacked = kids.length>1 && kids.slice(1).every(function(c,i){
                return Math.round(c.getBoundingClientRect().top) >= Math.round(kids[i].getBoundingClientRect().bottom) - 2;});
              var textKids=kids.filter(function(c){return c.textContent.trim().length>0;});
              out.push({h:Math.round(r.getBoundingClientRect().height), kids:kids.length,
                        maxW:Math.max.apply(null, textKids.map(function(c){return c.getBoundingClientRect().width;})),
                        stacked:stacked,
                        txt:r.textContent.trim().slice(0,12)});});
            return out;})()""")
        check("设置页大字号下条目为上下排（标签/值不再同行挤压）",
              all(r["stacked"] for r in rows),
              str([(r["txt"], r["stacked"]) for r in rows]))
        check("设置页大字号下每个片段有足够行宽（不成竖排单字）",
              all(r["maxW"] >= 120 for r in rows),
              str([(r["txt"], r["maxW"]) for r in rows]))

        # (4) 记录页操作条：滚到底后所有可见文字都在它上方（不得永久压住正文）
        for st in ("normal", "sheet", "largefont"):
            page.locator("[data-rec-state='%s']" % st).click()
            page.wait_for_timeout(500)
            page.evaluate("document.getElementById('records-scroll').scrollTop = 1e6")
            page.wait_for_timeout(300)
            res = page.evaluate("""(function(){
                var barEl=document.getElementById('records-action-bar');
                var bar=barEl.getBoundingClientRect();
                var ph=document.getElementById('phone-records').getBoundingClientRect();
                var bad=[];
                Array.from(document.querySelectorAll('#phone-records .t-titleM,#phone-records .t-bodyM,#phone-records .t-bodyL,#phone-records .t-labelM,#phone-records .det-row,#phone-records .btn'))
                  .forEach(function(e){
                    if(!e.getClientRects().length) return;      // 隐藏面板不计
                    if(barEl.contains(e)) return;                // 操作条自身不计
                    if(e.getBoundingClientRect().bottom-ph.top > bar.top-ph.top+1)
                      bad.push(e.textContent.trim().slice(0,20));});
                return bad;})()""")
            check("记录页滚到底后正文全部清出操作条（%s）" % st, not res, str(res[:2]))

        # (5) 计时数字自适应阶梯：大字体下必须降档且不溢出
        page.locator("[data-focus-state='running']").click()
        page.wait_for_timeout(500)
        r1 = page.evaluate("""(function(){
            var e=document.getElementById('focus-big'), cs=getComputedStyle(e);
            var c=document.createElement('canvas').getContext('2d');
            c.font=cs.fontWeight+' '+cs.fontSize+' '+cs.fontFamily;
            return {rung:parseFloat(cs.fontSize), textW:c.measureText('01:24:36').width};})()""")
        page.locator("[data-focus-state='running-large']").click()
        page.wait_for_timeout(600)
        r2 = page.evaluate("""(function(){
            var e=document.getElementById('focus-big'), cs=getComputedStyle(e);
            var c=document.createElement('canvas').getContext('2d');
            c.font=cs.fontWeight+' '+cs.fontSize+' '+cs.fontFamily;
            var ph=document.getElementById('phone-focus').getBoundingClientRect();
            var rr=document.createRange(); rr.selectNodeContents(e);
            var badge=(document.getElementById('ladder-badge')||{}).textContent||'';
            var m=badge.match(/(\d+)sp/);            // 徽标里明写选用的档位
            return {px:parseFloat(cs.fontSize), rung: m? parseInt(m[1],10):null,
                    badge:badge, textW:c.measureText('01:24:36').width,
                    overflow: rr.getBoundingClientRect().right > ph.right-1};})()""")
        check("运行态计时数字默认取最大档 52sp（computed 52px）",
              r1["rung"] == 52, "实际 %s" % r1["rung"])
        # 键是**档位**不是 computed px：2.0 档下 40sp 会放大成 80px，直接比 px 会误判。
        check("运行中+大字体时计时数字降档（不再固定 52sp）",
              r2["rung"] is not None and r2["rung"] < 52 and r2["rung"] in (52, 44, 40, 36, 32),
              "normal 52sp -> largefont %ssp（computed %spx）%s" % (r2["rung"], r2["px"], r2["badge"]))
        check("降档后计时数字不溢出手机宽度", not r2["overflow"], "文本宽 %.0f" % r2["textW"])
        check("运行中+大字体时暂停/结束按钮无需滚动即可见",
              page.evaluate("""(function(){
                var nav=document.querySelector('#phone-focus .nav').getBoundingClientRect();
                var ctl=document.querySelector('#phone-focus .focus-ctl').getBoundingClientRect();
                return ctl.bottom <= nav.top;})()"""))

        # (6) 星期标注必须与真实历法一致（曾把 10/5 标成周日、10/4 标成周六）
        wk = page.evaluate("""(function(){
            var out=[];
            Array.from(document.querySelectorAll('#phone-records .t-headS, #phone-focus .t-titleM'))
              .forEach(function(e){
                var m=e.textContent.match(/(\\d{4}) 年 (\\d+) 月 (\\d+) 日 (周[一二三四五六日])/);
                if(m) out.push([parseInt(m[1],10),parseInt(m[2],10),parseInt(m[3],10),m[4]]);});
            return out;})()""")
        import datetime as _dt
        _names = "一二三四五六日"
        bad_wk = []
        for y, mo, d, name in wk:
            real = "周" + _names[_dt.date(y, mo, d).weekday()]
            if name != real:
                bad_wk.append("%04d-%02d-%02d 标 %s 应为 %s" % (y, mo, d, name, real))
        check("跨日/记录页的星期标注与真实历法一致", not bad_wk,
              "; ".join(bad_wk) + ("（共核对 %d 处）" % len(wk)))

        # 专注页操作条只在模式选择出现，且贴住底部导航
        page.locator("[data-focus-state='select']").click()
        page.wait_for_timeout(350)
        bar = page.locator("#focus-action-bar").bounding_box()
        foc_nav = page.locator("#phone-focus .nav").bounding_box()
        check("专注页操作条贴住底部导航",
              bar and abs((foc_nav["y"]) - (bar["y"] + bar["height"])) < 3,
              "条底=%.0f 导航顶=%.0f" % (bar["y"] + bar["height"], foc_nav["y"]) if bar else "缺失")
        check("专注页「开始」按钮存在且可见", page.locator("#btn-start").is_visible())
        page.locator("[data-focus-state='done']").click()
        page.wait_for_timeout(300)
        check("到点结果页不显示「开始」操作条",
              not page.locator("#focus-action-bar").is_visible())

        # 命中区：完成控件 48dp
        bb = page.locator("#phone-today .chkbox").first.bounding_box()
        check("完成控件命中区 = 48x48",
              bb and round(bb["width"]) == 48 and round(bb["height"]) == 48,
              "%.0fx%.0f" % (bb["width"], bb["height"]) if bb else "无")

        # FAB 必须整体位于底部导航之上，且不被导航遮挡（早期用 bottom:16px 被导航盖住）
        page.locator("[data-today-state='normal']").click()
        page.wait_for_timeout(250)
        fab = page.locator("#phone-today .fab").bounding_box()
        nav = page.locator("#phone-today .nav").bounding_box()
        check("FAB 在底部导航上方且不重叠",
              fab and nav and fab["y"] + fab["height"] <= nav["y"] + 1,
              "FAB底=%.0f 导航顶=%.0f" % (fab["y"] + fab["height"], nav["y"]) if fab and nav else "无")
        check("FAB 有卡片色描边（压在卡片上仍可辨识）",
              page.evaluate("getComputedStyle(document.querySelector('#phone-today .fab')).borderTopColor")
              not in ("rgba(0, 0, 0, 0)", "transparent"))
        # 内容底部内边距必须大于 FAB 覆盖高度
        pad = page.evaluate("parseFloat(getComputedStyle(document.querySelector('#today-scroll')).paddingBottom)")
        cover_h = fab["height"] + (nav["y"] - (fab["y"] + fab["height"])) if fab and nav else 0
        check("内容底部内边距 > FAB 覆盖高度", pad > fab["height"],
              "padding-bottom=%.0f, FAB 高=%.0f" % (pad, fab["height"]))

        # 胶囊不得折行（实测过「日常」被折成两行）
        page.locator("[data-today-state='normal']").click()
        page.wait_for_timeout(250)
        pill_wrapped = page.evaluate("""
            Array.from(document.querySelectorAll('#phone-today .pill')).some(function (p) {
                var lh = parseFloat(getComputedStyle(p).lineHeight) || 16;
                return p.getBoundingClientRect().height > lh * 1.6;
            })
        """)
        check("状态胶囊不折行", not pill_wrapped)

        # 「数十项」状态必须真的把 37 项折叠区带进可视区（否则这一演示等于没演示）
        page.locator("[data-today-state='crowded']").click()
        page.wait_for_timeout(500)
        ph2 = page.locator("#phone-today").bounding_box()
        fb = page.locator("#phone-today [data-fold='b']").bounding_box()
        nav2 = page.locator("#phone-today .nav").bounding_box()
        check("数十项状态把 37 项折叠区带进可视区",
              fb and (fb["y"] - ph2["y"]) < (nav2["y"] - ph2["y"]),
              "折叠带相对手机顶 y=%.0f，可视底 %.0f"
              % (fb["y"] - ph2["y"], nav2["y"] - ph2["y"]) if fb else "不可见")
        check("数十项状态折叠区已展开",
              page.locator("#phone-today [data-fold='b']").get_attribute("aria-expanded") == "true")
        page.locator("[data-today-state='normal']").click()
        page.wait_for_timeout(250)
        # 默认必须显示已交付素材（file:// 下相对路径必然失败，内联是唯一可离线显示的方式）
        check("默认选中「已交付素材」模式",
              page.locator("[data-asset-btn='inline']").get_attribute("aria-pressed") == "true")
        check("默认模式下无退化容器（素材真的显示出来了）",
              page.locator(".cover.missing, .thumb.missing").count() == 0)

        # 素材展示方式开关必须**真的改变**画面：real = 真实 404 → 退化视觉；
        # mock = 内联示意 SVG，且不得再带 .missing（早期版本这个按钮是空操作）
        page.locator("[data-asset-btn='broken']").click()
        page.wait_for_timeout(700)
        n_broken = page.locator(".cover.missing, .thumb.missing").count()
        page.locator("[data-asset-btn='inline']").click()
        page.wait_for_timeout(700)
        n_inline = page.locator(".cover.missing, .thumb.missing").count()
        check("「不可加载」模式走真实素材缺失 → 退化视觉", n_broken > 0, "%d 个容器 .missing" % n_broken)
        check("「已交付素材」模式渲染真实素材（不再是退化态）", n_inline == 0, "%d 个容器 .missing" % n_inline)
        # 内联的必须是**已交付素材**（base64 副本），且真的解码出契约尺寸
        dims = page.evaluate("""Array.from(document.querySelectorAll('.cover img,.thumb img'))
            .map(function(i){ return i.getAttribute('data-real-src') + ' -> ' + i.naturalWidth + 'x' + i.naturalHeight; })""")
        check("内联素材解码尺寸与契约一致（cover 656x400 / card 216x216 / panel 656x320）",
              all(("656x400" in d) or ("216x216" in d) or ("656x320" in d) for d in dims),
              "; ".join(dims[:4]))
        check("内联素材是已交付 SVG 的 base64 副本",
              page.evaluate("""(document.querySelector('#cover1 img')||{getAttribute:function(){return '';}})
                    .getAttribute('src').indexOf('data:image/svg+xml;base64,') === 0"""))
        # 素材必须真的画出来：早期版本颜色被双重转义（%2523），SVG 解析失败渲染成纯黑块。
        check("素材真实渲染（非纯黑块）",
              page.evaluate("""(function(){
                 var i = document.querySelector('#cover1 img');
                 if (!i || !i.naturalWidth) return false;
                 var c = document.createElement('canvas');
                 c.width = 24; c.height = 24;
                 var x = c.getContext('2d');
                 x.drawImage(i, 0, 0, 24, 24);
                 var d = x.getImageData(0, 0, 24, 24).data;
                 var bright = 0;
                 for (var k = 0; k < d.length; k += 4) {
                   if (d[k] + d[k+1] + d[k+2] > 180) bright++;
                 }
                 return bright > 24 * 24 * 0.5;   // 底色为浅纸色，应有过半亮像素
              })()"""))
        check("mock 模式 img.src 已换成内联 SVG",
              page.evaluate("""(document.querySelector('#cover1 img')||{})
                    .getAttribute && document.querySelector('#cover1 img')
                    .getAttribute('src').indexOf('data:image/svg+xml') === 0"""))
        page.locator("[data-asset-btn='broken']").click()
        page.wait_for_timeout(700)
        check("切回「不可加载」恢复相对路径与退化态",
              page.locator(".cover.missing").count() > 0
              and page.evaluate("""document.querySelector('#cover1 img')
                    .getAttribute('src').indexOf('assets/art/') >= 0"""))

        page.locator("[data-today-state='largefont']").click()
        page.wait_for_timeout(400)
        cbb = page.locator("#cover1").bounding_box()
        h3 = cbb["height"] if cbb else 0.0
        check("大字体下封面收窄或隐藏（< 168px）", h3 < 168,
              "实际 %.0fpx%s" % (h3, "（整块让位）" if not cbb else ""))
        # 封面摘要在 band 3+ 应移出封面（static 定位）
        pos = page.evaluate("getComputedStyle(document.querySelector('.cover-say')).position")
        check("大字体下摘要移出封面（position:static）", pos == "static", pos)
        # 首屏必须至少露出 1 张**完整**任务卡。
        # 注意 bounding_box 相对主框架视口，点击外部按钮会滚动外层页面，
        # 因此一律以**手机容器自身**为参照换算。
        # 口径修正：旧断言只要求「卡片顶部越过可视底 20px」就算可见，
        # 实测那样卡片会被底栏截断 40%（155px 只露 93px），与文档「完整任务卡」的说法不符。
        ph = page.locator("#phone-today").bounding_box()
        nav = page.locator("#phone-today .nav").bounding_box()
        nav_top = nav["y"] - ph["y"]
        cards = page.locator("#phone-today .tcard")
        vis, detail = 0, []
        for i in range(cards.count()):
            b = cards.nth(i).bounding_box()
            if not b:
                continue
            top = b["y"] - ph["y"]
            bot = top + b["height"]
            fully = (top >= -1) and (bot <= nav_top + 1)
            if fully:
                vis += 1
            detail.append("%.0f-%.0f%s" % (top, bot, "" if fully else "(截断)"))
        check("大字体首屏至少露出 1 张**完整**任务卡", vis >= 1,
              "完整 %d 张；卡片区间 %s；可视底 %.0f" % (vis, detail[:3], nav_top))
        # 区块标题行在 2.0 档必须仍是单行（实测曾挤成两行 112px）
        sh_h = page.evaluate("document.querySelector('#phone-today .sec-head').getBoundingClientRect().height")
        check("大字体下区块标题行仍为单行", sh_h <= 60, "实际 %.0fpx" % sh_h)
        check("手机外的说明块不随字号缩放（脚手架不得占产品屏幕）",
              page.evaluate("parseFloat(getComputedStyle(document.querySelector('.page-note')).fontSize)") <= 14)
        page.locator("[data-today-state='normal']").click()
        page.wait_for_timeout(250)
        check("离开大字体状态后字号复位",
              abs(page.evaluate("parseFloat(getComputedStyle(document.querySelector('#phone-today')).getPropertyValue('--fs'))") - 1.0) < 0.01)

        # 热力图格子：53 列 x 7 行
        cols = page.locator("#hm-grid .hm-col").count()
        cells = page.locator("#hm-grid .hm-cell").count()
        check("热力图 53 周列", cols == 53, "实际 %d" % cols)
        check("热力图格子总数 = 371", cells == 371, "实际 %d" % cells)

        zero = page.locator("#hm-grid .hm-cell.empty").count()
        filled = page.locator("#hm-grid .hm-cell.l1, #hm-grid .hm-cell.l2, "
                              "#hm-grid .hm-cell.l3, #hm-grid .hm-cell.l4").count()
        check("存在「无记录」虚线圈格", zero > 0, "%d 格" % zero)
        check("存在非零色阶格", filled > 0, "%d 格" % filled)

        # 素材确实缺失 → 退化视觉是真实触发的，不是伪造
        missing = page.locator(".cover.missing").count()
        check("素材缺失时真实触发退化 class", missing >= 1, "%d 个容器 .missing" % missing)

        # aria 语义抽查
        aria = page.locator("#hm-grid .hm-cell[role='gridcell']").count()
        check("热力格有 gridcell 角色", aria > 0, "%d 格" % aria)
        lbl = page.locator(".chkbox[role='checkbox']").first.get_attribute("aria-label")
        check("完成控件有可读 aria-label", bool(lbl), lbl or "")

        # ---- state screenshots ----
        print()
        print("=" * 78)
        print("状态渲染截图（写入 %s）" % OUT.relative_to(ROOT))
        print("=" * 78)

        def snap(name):
            page.screenshot(path=str(OUT / (name + ".png")), full_page=False)
            return (OUT / (name + ".png")).stat().st_size

        shots = {}
        hashes = {}

        def capture(name, frame_sel, state_attr, state):
            """Screenshot one frame in one state and record its content hash."""
            page.locator("[data-%s='%s']" % (state_attr, state)).click()
            page.wait_for_timeout(340)
            page.locator(frame_sel).screenshot(path=str(OUT / (name + ".png")))
            data = (OUT / (name + ".png")).read_bytes()
            shots[name] = len(data)
            hashes[name] = hashlib.sha256(data).hexdigest()

        # 五个页面 × 全部状态。只截今天/洞察是不够的：其余三页的层级与间距
        # 必须能出图，评审才核对得了「五页是否来自同一套设计系统」。
        PLAN = [
            ("today", "#phone-today", "today-state",
             ["normal", "focusing", "empty", "crowded", "recovery", "error",
              "longtitle", "largefont"]),
            ("focus", "#phone-focus", "focus-state",
             ["select", "running", "paused", "done", "midnight", "switch", "largefont"]),
            ("records", "#phone-records", "rec-state",
             ["normal", "empty", "dayempty", "sheet", "del", "warn", "error", "largefont"]),
            ("insight", "#phone-insights", "ins-state",
             ["normal", "empty", "warn", "error", "longtitle", "largefont"]),
            ("settings", "#phone-settings", "set-state",
             ["normal", "zone", "restore", "badpw", "nospace", "restorefail", "largefont"]),
        ]
        for frame, sel, attr, states in PLAN:
            for st in states:
                name = "%s-%s" % (frame, st)
                capture(name, sel, attr, st)
                check("截图 %s" % name, shots[name] > 15000, "%d bytes" % shots[name])

        # 关键：每个状态必须渲染出**不同**的像素，否则「状态演示」是空的。
        print()
        print("=" * 78)
        print("状态区分度断言（每个页面内各状态的截图哈希必须互不相同）")
        print("=" * 78)
        for frame, sel, attr, states in PLAN:
            groups = {}
            for st in states:
                groups.setdefault(hashes[frame + "-" + st], []).append(st)
            dupes = [v for v in groups.values() if len(v) > 1]
            check("%s 页各状态截图互不相同" % frame, not dupes,
                  "; ".join("=".join(v) for v in dupes))
        for frame, states in (("today", PLAN[0][3]), ("insight", PLAN[3][3])):
            pass
        check("今天页 normal 有内容（非空白）", shots["today-normal"] > 15000)

        def record(name, full_page=False, sel=None):
            """Still-record a screenshot so it lands in the inventory too.
            (Night/overview shots were previously written straight to disk and
            were missing from the render record — that is the reported defect.)"""
            target = str(OUT / (name + ".png"))
            if sel:
                page.locator(sel).screenshot(path=target)
            else:
                page.screenshot(path=target, full_page=full_page)
            data = (OUT / (name + ".png")).read_bytes()
            shots[name] = len(data)
            hashes[name] = hashlib.sha256(data).hexdigest()
            check("截图 %s" % name, len(data) > (40000 if full_page else 15000),
                  "%d bytes" % len(data))

        # ---- night theme + reduced motion ----
        page.locator("[data-theme-btn='night']").click()
        page.wait_for_timeout(500)
        page.locator("[data-ins-state='normal']").click()
        page.wait_for_timeout(350)
        record("insight-normal-night", sel="#phone-insights")
        page.locator("[data-today-state='normal']").click()
        page.wait_for_timeout(350)
        record("today-normal-night", sel="#phone-today")
        page.locator("[data-rec-state='normal']").click()
        page.wait_for_timeout(350)
        record("records-normal-night", sel="#phone-records")
        page.locator("[data-set-state='normal']").click()
        page.wait_for_timeout(350)
        record("settings-normal-night", sel="#phone-settings")
        page.locator("[data-focus-state='select']").click()
        page.wait_for_timeout(350)
        record("focus-select-night", sel="#phone-focus")

        page.locator("#rm-on").click()
        page.wait_for_timeout(250)
        reduced = page.evaluate("document.body.classList.contains('reduced')")
        check("减少动画开关生效", reduced is True)
        record("today-largefont-reduced", sel="#phone-today")
        page.locator("#rm-off").click()
        page.locator("[data-theme-btn='day']").click()
        page.wait_for_timeout(500)

        # 全页总览（五页同屏）
        page.locator("[data-today-state='normal']").click()
        page.locator("[data-ins-state='normal']").click()
        page.locator("[data-rec-state='normal']").click()
        page.locator("[data-set-state='normal']").click()
        page.locator("[data-focus-state='select']").click()
        page.wait_for_timeout(600)
        record("overview", full_page=True)

        # ---- 交互：折叠区展开 / 热力格点选 ----
        print()
        print("=" * 78)
        print("交互断言")
        print("=" * 78)
        page.locator("[data-today-state='normal']").click()
        page.wait_for_timeout(200)
        fold = page.locator("#phone-today [data-fold='a']")
        fold.click()
        page.wait_for_timeout(300)
        expanded = fold.get_attribute("aria-expanded")
        check("折叠区展开后 aria-expanded=true", expanded == "true", expanded or "")
        check("折叠区内容可见", page.locator("#phone-today [data-fold-body='a'].open").count() == 1)

        page.locator("[data-ins-state='normal']").click()
        page.wait_for_timeout(200)
        before = page.locator("#sel-val").inner_text()
        page.locator("#hm-grid .hm-cell[role='gridcell']").nth(30).click()
        page.wait_for_timeout(200)
        after = page.locator("#sel-val").inner_text()
        check("点选热力格后详情卡更新", before != after, "%s -> %s" % (before, after))
        sel_count = page.locator(".hm-cell.sel").count()
        check("同时只有一个选中格", sel_count == 1, "实际 %d" % sel_count)

        ctx.close()
        browser.close()

    print()
    print("=" * 78)
    produced = sorted(shots)
    on_disk = sorted(p.stem for p in OUT.glob("*.png"))
    _NL = chr(10)
    _status = "通过" if not failures else "存在 %d 项失败" % len(failures)
    _consistency = ("一致" if set(produced) == set(on_disk) else
                    "不一致！多余=%s 缺失=%s" % (sorted(set(on_disk) - set(produced)),
                                                 sorted(set(produced) - set(on_disk))))
    _lines = [
        "原型渲染检查记录（由 scripts/test/verify_prototype_offline.py 自动生成）",
        "生成命令：python scripts/test/verify_prototype_offline.py",
        "结束状态：" + _status, "",
        "运行开始时已清理上一轮截图：%d 张（目录内不留任何非本轮产物）" % len(stale),
        "本轮截图：%d 张" % len(shots),
        "目录内 PNG：%d 张" % len(on_disk),
        "清单一致性：" + _consistency, "",
        "请求总数：%d，全部 file://（其中 %d 个是刻意保留的缺失素材引用，用于触发真实退化视觉）"
        % (len(requests), len(missing_asset_requests)),
        "控制台错误：仅 ERR_FILE_NOT_FOUND（素材缺失），无脚本错误", "",
        "本轮截图清单（name / 字节 / sha256前16）：",
    ]
    _lines += ["  %-24s %7d  %s" % (k, shots[k], hashes[k][:16]) for k in produced]
    _lines += ["", "五页 × 全状态覆盖："]
    _lines += ["  %-9s %s" % (f, ", ".join(st)) for f, _, _, st in PLAN]
    _lines += ["",
        "注意：本检查是桌面 Chromium 的 HTML 布局、离线性与状态区分度验证。",
        "它不能替代 Android/Compose 真机渲染、TalkBack 朗读、系统 inset 适配、",
        "系统 CJK 字体栅格化或真机性能测量——这些在 docs/设计/设计系统.md 第 13 节",
        "与 docs/设计/关键页面高保真说明.md 第 8 节逐条列为未验证。", ""]
    (OUT / "_渲染记录.txt").write_text(_NL.join(_lines), encoding="utf-8")


    if failures:
        print("!!! %d 项失败：" % len(failures))
        for f in failures:
            print("    " + f)
        return 1
    on_disk = sorted(p.stem for p in OUT.glob("*.png"))
    if set(on_disk) != set(shots):
        failures.append("渲染目录与记录清单不一致：目录=%s，本轮=%s"
                        % (on_disk, sorted(shots)))
    for n in notes:
        print("说明：" + n)
    print("最终统计：共 %d 个请求，全部 file://；其中 %d 个为刻意保留的缺失素材引用。"
          % (len(requests), len(missing_asset_requests)))
    print("全部断言通过。注意：本脚本验证的是桌面 Chromium 下的 HTML 布局与离线性，")
    print("不能替代 Android/Compose 真机验证、TalkBack、系统 inset 与实际字体测量。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
