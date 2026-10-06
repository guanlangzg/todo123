//! Session state machine and effective-time accounting.
//!
//! Pause never accumulates: only intervals actually spent `Running` become `session_segment`
//! rows, so the sum of segment durations is the session's real investment (AC-05).

use crate::error::DomainError;
use crate::types::{RecoveryChoice, SessionState, WorkSegment};

/// Allowed transitions of 架构契约 §5.1. `None` means "no session yet".
pub fn validate_transition(from: Option<SessionState>, to: SessionState) -> Result<(), DomainError> {
    let allowed = matches!(
        (from, to),
        (None, SessionState::Running)
            | (Some(SessionState::Running), SessionState::Paused)
            | (Some(SessionState::Running), SessionState::Finished)
            | (Some(SessionState::Running), SessionState::RecoveryPending)
            | (Some(SessionState::Paused), SessionState::Running)
            | (Some(SessionState::Paused), SessionState::Finished)
            | (Some(SessionState::Paused), SessionState::RecoveryPending)
            | (Some(SessionState::RecoveryPending), SessionState::Finished)
    );
    if allowed {
        Ok(())
    } else {
        Err(DomainError::PreconditionFailed {
            reason: format!("illegal session transition {from:?} -> {to:?}"),
        })
    }
}

fn closed_seconds(segment: &WorkSegment, now_wall_ms: i64) -> i64 {
    let end = segment.end_wall_ms.unwrap_or(now_wall_ms);
    if end <= segment.start_wall_ms {
        0
    } else {
        (end - segment.start_wall_ms) / 1000
    }
}

/// Seconds already booked by the segments that have been closed.
///
/// A pause closes (and books) the span it ends, so this is the part of a session whose investment is
/// already a fact; anything on top of it has to come from the open segment.
pub fn closed_seconds_total(segments: &[WorkSegment], session_id: &str) -> i64 {
    segments
        .iter()
        .filter(|segment| segment.session_id == session_id && segment.end_wall_ms.is_some())
        .map(|segment| closed_seconds(segment, segment.start_wall_ms))
        .sum()
}

/// Wall instant at which a session's investment reaches `total_seconds`.
///
/// The screens and the foreground service tell the domain *the number the clock shows*, and that
/// number is the session's accumulated investment: the focus screen sums every segment, the stop
/// button reads 「已投入 …」, and the countdown service counts the same running total. Turning that
/// number into an end instant therefore means taking the already-closed segments as spent and mapping
/// only the remainder into the open segment — applying the total to the open segment alone would
/// count every already-booked span twice (AC-05 / AC-14).
///
/// `None` means the session has no open segment, so there is nothing left to close.
///
/// The result is a pure function of the segment layout and `total_seconds`: it deliberately does
/// **not** depend on the sampled command clock, because the whole point of booking a displayed number
/// is that the result equals what the screen showed. The end is floored at the open segment's start,
/// so a total the closed segments have already spent books nothing rather than going backwards.
pub fn total_end_wall_ms(segments: &[WorkSegment], session_id: &str, total_seconds: i64) -> Option<i64> {
    let open = segments
        .iter()
        .find(|segment| segment.session_id == session_id && segment.end_wall_ms.is_none())?;
    let closed = closed_seconds_total(segments, session_id);
    let remaining_seconds = (total_seconds - closed).max(0);
    Some(open.start_wall_ms + remaining_seconds * 1000)
}

/// Seconds of a session that count as investment: every closed segment, plus any still-open
/// segment measured up to `now_wall_ms`. Paused spans are absent from `segments` entirely.
pub fn effective_seconds(session_id: &str, segments: &[WorkSegment], now_wall_ms: i64) -> i64 {
    segments
        .iter()
        .filter(|segment| segment.session_id == session_id)
        .map(|segment| closed_seconds(segment, now_wall_ms))
        .sum()
}

/// Seconds that are safe to book for a session interrupted by a process death.
///
/// `heartbeat_wall_ms` is the last *successfully persisted* anchor, so `[start, heartbeat]` is
/// trustworthy and `[heartbeat, recovery]` is the gap the user must adjudicate (架构契约 §7.1).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RecoveryAmounts {
    pub trusted_seconds: i64,
    pub gap_seconds: i64,
    pub booked_seconds: i64,
}

pub fn recovery_amounts(
    start_wall_ms: i64,
    heartbeat_wall_ms: i64,
    recovery_wall_ms: i64,
    choice: &RecoveryChoice,
    target_seconds: Option<i64>,
) -> Result<RecoveryAmounts, DomainError> {
    if heartbeat_wall_ms < start_wall_ms {
        return Err(DomainError::PreconditionFailed {
            reason: "heartbeat precedes segment start".to_string(),
        });
    }
    let trusted = (heartbeat_wall_ms - start_wall_ms) / 1000;
    let gap = ((recovery_wall_ms - heartbeat_wall_ms).max(0)) / 1000;
    let booked = match choice {
        RecoveryChoice::Accept => {
            let mut total = trusted + gap;
            if let Some(target) = target_seconds {
                // A countdown caps the whole session; the caller subtracts earlier closed segments.
                total = total.min(target);
            }
            total
        }
        RecoveryChoice::Modify { seconds } => {
            if *seconds < 0 {
                return Err(DomainError::NegativeSeconds);
            }
            *seconds
        }
        RecoveryChoice::Discard => trusted,
    };
    Ok(RecoveryAmounts {
        trusted_seconds: trusted,
        gap_seconds: gap,
        booked_seconds: booked,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seg(seq: u32, start: i64, end: Option<i64>) -> WorkSegment {
        WorkSegment {
            segment_id: format!("s:1:{seq}"),
            session_id: "s".to_string(),
            seg_seq: seq,
            start_wall_ms: start,
            end_wall_ms: end,
            title_snapshot: "速写".to_string(),
            zone_epoch_seq: 1,
            derived: false,
        }
    }

    /// AC-05: 10 min running + 3 min paused + 5 min running = 900 s, not 1080 s.
    #[test]
    fn pause_does_not_accumulate() {
        let segments = vec![seg(1, 0, Some(600_000)), seg(2, 780_000, Some(1_080_000))];
        assert_eq!(effective_seconds("s", &segments, 2_000_000), 900);
    }

    #[test]
    fn open_segment_counts_up_to_now() {
        let segments = vec![seg(1, 0, Some(600_000)), seg(2, 720_000, None)];
        assert_eq!(effective_seconds("s", &segments, 900_000), 780);
    }

    #[test]
    fn other_sessions_are_excluded() {
        let mut other = seg(1, 0, Some(60_000));
        other.session_id = "other".to_string();
        let segments = vec![seg(1, 0, Some(600_000)), other];
        assert_eq!(effective_seconds("s", &segments, 0), 600);
    }

    #[test]
    fn transitions_follow_the_contract() {
        assert!(validate_transition(None, SessionState::Running).is_ok());
        assert!(validate_transition(Some(SessionState::Running), SessionState::Paused).is_ok());
        assert!(validate_transition(Some(SessionState::Paused), SessionState::Running).is_ok());
        assert!(validate_transition(Some(SessionState::Paused), SessionState::Finished).is_ok());
        assert!(validate_transition(Some(SessionState::RecoveryPending), SessionState::Finished).is_ok());
        assert!(validate_transition(None, SessionState::Finished).is_err());
        assert!(validate_transition(Some(SessionState::Finished), SessionState::Running).is_err());
        assert!(validate_transition(Some(SessionState::RecoveryPending), SessionState::Running).is_err());
    }

    /// AC-09: discard books only the trusted span; the gap stays unbooked.
    #[test]
    fn recovery_choices_book_the_right_amount() {
        let (start, heartbeat, recovery) = (0_i64, 300_000_i64, 900_000_i64);
        let accept =
            recovery_amounts(start, heartbeat, recovery, &RecoveryChoice::Accept, None).expect("accept");
        assert_eq!(accept.trusted_seconds, 300);
        assert_eq!(accept.gap_seconds, 600);
        assert_eq!(accept.booked_seconds, 900);

        let discard =
            recovery_amounts(start, heartbeat, recovery, &RecoveryChoice::Discard, None).expect("discard");
        assert_eq!(discard.booked_seconds, 300);

        let modify = recovery_amounts(
            start,
            heartbeat,
            recovery,
            &RecoveryChoice::Modify { seconds: 420 },
            None,
        )
        .expect("modify");
        assert_eq!(modify.booked_seconds, 420);

        let capped =
            recovery_amounts(start, heartbeat, recovery, &RecoveryChoice::Accept, Some(600)).expect("capped");
        assert_eq!(capped.booked_seconds, 600);
    }

    #[test]
    fn negative_modify_is_rejected() {
        let err = recovery_amounts(0, 1_000, 2_000, &RecoveryChoice::Modify { seconds: -5 }, None);
        assert!(matches!(err, Err(DomainError::NegativeSeconds)));
    }

    #[test]
    fn heartbeat_before_start_is_rejected() {
        let err = recovery_amounts(5_000, 1_000, 9_000, &RecoveryChoice::Accept, None);
        assert!(matches!(err, Err(DomainError::PreconditionFailed { .. })));
    }
}
