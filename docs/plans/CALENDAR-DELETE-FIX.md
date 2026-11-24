# Calendar "duplicate" delete: findings (2026-11-24 02:35 CAT). Paused on Finn's word, nothing built or pushed.
Local copy: repo/ = archive of main 8e74c103 (0.55.12 / vc 221), plain git init, no edits.
Root causes: (1) calendar_events lists events with no id and no count, so two reads of one event looked like two copies;
(2) PimEdit.deleteEvent deletes the first title match, with no id, no duplicate check, no snapshot;
(3) the delete text formats all-day UTC midnight in local time, which gives "02:00";
(4) ChatToolRows.stepOf / LivingModel.match pair rows to results by args with no time check, so older turns' results show under new steps;
(5) ClaimCheck.RUNNING + bare "Yes" (FollowUpBind.isShortYes) flagged a "build is running" status line, so ActionProof.honest said "nothing did it";
(6) a 30-min routine never touches the calendar (RoutineCalendar.wanted false, remove(0) is a no-op). The "routine duplicate" was invented by the model.
Proposed version 0.56.1 / vc 223 (MR !78 takes 0.56.0 / 222).
