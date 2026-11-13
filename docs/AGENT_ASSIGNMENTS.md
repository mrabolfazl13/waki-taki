# Agent Assignments & File Ownership

| Agent | Role | Owns (exclusive write) |
|---|---|---|
| A (this coordinator) | Chief Architect | docs/ARCHITECTURE.md, docs/DECISIONS.md, integration |
| B+C+D+E | Android team (core, audio, security, offline) | android/** |
| F | Backend | backend/**, backend/tests/** |
| G | Web dashboard | web/** |
| H | QA | backend/tests/security/**, docs/TEST_RESULTS.md, docs/KNOWN_ISSUES.md |
| J | DevOps | deploy/**, .github/workflows/**, Dockerfiles |

Shared, coordination-required files: docs/PROGRESS.md (append-only entries), root README.md (A only).

## PROGRESS.md entry format

```
### <TASK-ID> — <description>
- Agent: <X> | Status: NOT_STARTED|IN_PROGRESS|BLOCKED|IMPLEMENTED|TESTED|VERIFIED
- Depends: <ids> | Files: <paths>
- Details: <what was implemented>
- Tests: <executed> → <actual results>
- Acceptance: <criteria> | Remaining: <work>
```

A task is TESTED only after its tests actually ran; VERIFIED only after acceptance criteria demonstrated.
