//! AC-02 (the `rust-domain` half): group ordering.
//!
//! Sorting is a per-group fact. `ReorderTasks` rewrites the whole group's `sort_key` in **one**
//! command — a partial or cross-group list must be refused *before* anything is written, because
//! Kotlin persists whichever effects come back and a half-applied reorder would leave duplicate or
//! colliding keys behind.
//!
//! Gate target: `cargo test --manifest-path rust/Cargo.toml --test ordering`

use arttodo_core::*;

fn reduce(state: &DomainState, command: DomainCommand, envelope: &CommandEnvelope) -> DomainOutcome {
    arttodo_core::reduce(state.clone(), command, envelope.clone())
}

fn initial_state(zone_id: &str, created_wall_ms: i64) -> DomainState {
    arttodo_core::initial_state(zone_id.to_string(), created_wall_ms)
}

const ZONE: &str = "Asia/Shanghai";
const BASE: i64 = 1_772_000_000_000;
/// 架构契约 §4.2 [设计默认]: one step per slot inside a group.
const STEP: i64 = 1024;

fn envelope(id: &str, wall_ms: i64, revision: u64) -> CommandEnvelope {
    CommandEnvelope {
        command_id: id.to_string(),
        issued_at: ClockSample {
            wall_ms,
            zone_id: ZONE.to_string(),
            elapsed_ms: 0,
            boot_tag: String::new(),
        },
        expected_revision: revision,
    }
}

/// Creates a task through the command surface and returns its domain-assigned id. The id is
/// `task:<command_id>` (架构契约 §6: deterministic, minted once and persisted — never a random UUID).
fn create(state: &DomainState, kind: TaskKind, title: &str, id: &str, wall_ms: i64) -> DomainState {
    let outcome = reduce(
        state,
        DomainCommand::CreateTask {
            kind,
            title: title.to_string(),
            note: String::new(),
        },
        &envelope(id, wall_ms, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    outcome.next_state
}

fn sort_key_of(state: &DomainState, task_id: &str) -> i64 {
    state
        .tasks
        .iter()
        .find(|task| task.task_id == task_id)
        .unwrap_or_else(|| panic!("task {task_id}"))
        .sort_key
}

fn three_dailies() -> (DomainState, [String; 3]) {
    let mut state = initial_state(ZONE, BASE);
    let mut ids = Vec::new();
    for (index, id) in ["create-a", "create-b", "create-c"].iter().enumerate() {
        state = create(
            &state,
            TaskKind::Daily,
            &format!("日常{}", index + 1),
            id,
            BASE + index as i64 * 1_000,
        );
        ids.push(format!("task:{id}"));
    }
    (state, [ids[0].clone(), ids[1].clone(), ids[2].clone()])
}

/// Creation already spaces the group by one step, in creation order.
#[test]
fn creation_numbers_a_group_by_step() {
    let (state, [a, b, c]) = three_dailies();
    assert_eq!(sort_key_of(&state, &a), STEP);
    assert_eq!(sort_key_of(&state, &b), STEP * 2);
    assert_eq!(sort_key_of(&state, &c), STEP * 3);
}

/// One command rewrites the whole group, in the given order.
#[test]
fn reordering_a_group_writes_the_new_order_in_one_command() {
    let (state, [a, b, c]) = three_dailies();
    let outcome = reduce(
        &state,
        DomainCommand::ReorderTasks {
            kind: TaskKind::Daily,
            ordered_task_ids: vec![c.clone(), a.clone(), b.clone()],
        },
        &envelope("reorder", BASE + 10_000, state.revision),
    );
    assert!(outcome.error.is_none(), "{:?}", outcome.error);
    let state = outcome.next_state;

    assert_eq!(sort_key_of(&state, &c), STEP, "C is now first");
    assert_eq!(sort_key_of(&state, &a), STEP * 2, "A is now second");
    assert_eq!(sort_key_of(&state, &b), STEP * 3, "B is now third");

    // The whole group moved in a single command, not one command per row.
    let writes = outcome
        .effects
        .iter()
        .filter(|effect| matches!(effect, LedgerEffect::UpsertTask { .. }))
        .count();
    assert_eq!(writes, 3, "one command must persist the whole group");
    // Two different groups must never share one sort_key namespace by accident: the keys here are
    // strictly increasing, which is what the UI sorts on.
    let mut keys: Vec<i64> = state
        .tasks
        .iter()
        .filter(|task| task.kind == TaskKind::Daily)
        .map(|task| task.sort_key)
        .collect();
    keys.sort_unstable();
    assert_eq!(keys, vec![STEP, STEP * 2, STEP * 3]);
}

/// Reordering so that the same list comes back is stable: the keys are a function of the list, not
/// of the previous order.
#[test]
fn reordering_is_a_function_of_the_requested_order() {
    let (state, [a, b, c]) = three_dailies();
    let reorder = |state: &DomainState, order: Vec<String>, id: &str| {
        let outcome = reduce(
            state,
            DomainCommand::ReorderTasks {
                kind: TaskKind::Daily,
                ordered_task_ids: order,
            },
            &envelope(id, BASE + 10_000, state.revision),
        );
        assert!(outcome.error.is_none(), "{:?}", outcome.error);
        outcome.next_state
    };

    let once = reorder(&state, vec![c.clone(), a.clone(), b.clone()], "r1");
    let again = reorder(&once, vec![c.clone(), a.clone(), b.clone()], "r2");
    for task in &again.tasks {
        assert_eq!(task.sort_key, sort_key_of(&once, &task.task_id));
    }
    // Reversing the request really does reverse the group.
    let reversed = reorder(&again, vec![b.clone(), a.clone(), c.clone()], "r3");
    assert_eq!(sort_key_of(&reversed, &b), STEP);
    assert_eq!(sort_key_of(&reversed, &c), STEP * 3);
}

/// A task from another group cannot be dragged into this group's order, and the refusal writes
/// nothing.
#[test]
fn a_task_from_another_group_is_refused_and_nothing_is_written() {
    let (mut state, [a, b, c]) = three_dailies();
    state = create(&state, TaskKind::Temporary, "临时任务", "create-temp", BASE + 500);
    let temporary = "task:create-temp".to_string();

    let before: Vec<(String, i64)> = state
        .tasks
        .iter()
        .map(|task| (task.task_id.clone(), task.sort_key))
        .collect();

    // A temporary task listed under the daily group.
    let outcome = reduce(
        &state,
        DomainCommand::ReorderTasks {
            kind: TaskKind::Daily,
            ordered_task_ids: vec![a.clone(), temporary.clone(), b.clone(), c.clone()],
        },
        &envelope("cross-group", BASE + 20_000, state.revision),
    );
    assert!(
        matches!(outcome.error, Some(DomainError::PreconditionFailed { .. })),
        "{:?}",
        outcome.error
    );
    assert!(
        outcome.effects.is_empty(),
        "a refused reorder must not write any sort_key: {:?}",
        outcome.effects
    );
    assert_eq!(outcome.next_state.revision, state.revision);
    let after: Vec<(String, i64)> = outcome
        .next_state
        .tasks
        .iter()
        .map(|task| (task.task_id.clone(), task.sort_key))
        .collect();
    assert_eq!(before, after, "the group order must be untouched");

    // And the mirror case: a daily listed under the temporary group.
    let mirror = reduce(
        &state,
        DomainCommand::ReorderTasks {
            kind: TaskKind::Temporary,
            ordered_task_ids: vec![a.clone()],
        },
        &envelope("cross-group-2", BASE + 30_000, state.revision),
    );
    assert!(matches!(
        mirror.error,
        Some(DomainError::PreconditionFailed { .. })
    ));
    assert!(mirror.effects.is_empty());
}

/// An unknown task in the list stops the whole command: nothing is written, not even for the valid
/// entries that came before it.
#[test]
fn an_unknown_task_stops_the_whole_reorder() {
    let (state, [a, b, c]) = three_dailies();
    let before: Vec<i64> = state.tasks.iter().map(|task| task.sort_key).collect();

    let outcome = reduce(
        &state,
        DomainCommand::ReorderTasks {
            kind: TaskKind::Daily,
            ordered_task_ids: vec![c.clone(), "task:nope".to_string(), a.clone(), b.clone()],
        },
        &envelope("unknown", BASE + 10_000, state.revision),
    );
    assert!(matches!(outcome.error, Some(DomainError::TaskNotFound { .. })));
    assert!(outcome.effects.is_empty());
    assert_eq!(
        outcome
            .next_state
            .tasks
            .iter()
            .map(|t| t.sort_key)
            .collect::<Vec<_>>(),
        before
    );
}

/// The whole reorder is one user action, so it carries one command id and is applied once.
#[test]
fn repeating_the_same_reorder_id_is_a_duplicate() {
    let (state, [a, b, c]) = three_dailies();
    let envelope = envelope("reorder-once", BASE + 10_000, state.revision);
    let command = DomainCommand::ReorderTasks {
        kind: TaskKind::Daily,
        ordered_task_ids: vec![c, b, a],
    };

    let first = reduce(&state, command.clone(), &envelope);
    assert!(first.error.is_none(), "{:?}", first.error);
    let second = reduce(&first.next_state, command, &envelope);
    assert!(matches!(second.error, Some(DomainError::DuplicateCommand { .. })));
    assert!(second.effects.is_empty());
    assert_eq!(second.next_state.tasks, first.next_state.tasks);
}
