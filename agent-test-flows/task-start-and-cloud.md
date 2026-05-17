# Task Start And Cloud

Use this runbook to verify task start flows.

## What To Test

- Start a task with no stream active.
- Start streaming, then start a task while the stream is active.
- Cancel the task.
- Confirm task status reaches running, canceled, or another terminal state.
- Confirm state requests still work during the task.

## Cloud Checks

Run cloud-backed task checks only when `<mobilerun-api-key-env>` is available. If the app is already connected, use the existing credentials. If setup is needed, the agent may enter the key through the normal UI with taps and text input. Do not print the key, and record whether auth was preexisting or injected for this run.

Use a read-only prompt:

```text
Open Settings and tell me which Android version is installed. Do not change any settings.
```

## Evidence To Collect

- Task prompt used.
- UI screenshot or dump before and after task start.
- Reverse messages and task status.
- Stream state if a stream was active.
- Logs for task prompt, cloud client, reverse connection, screenshot, and streaming failures.
