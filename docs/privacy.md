# Privacy and data controls

This document describes the LeftOVERS application boundary and the current Vertex AI runtime. It does not promise provider Zero Data Retention or replace Google Cloud or OpenAI terms.

## Data kept on the Android device

Pantry rows, equipment choices, recipe snapshots, active cooking sessions, preference and feedback signals, meal notes, history, and a user-selected final-dish photo remain in app-private local storage. LeftOVERS has no account UI or product-data cloud backup. Firebase anonymous authentication protects backend access; it is not a user profile.

A final-dish photo is retained locally only when the user explicitly selects it during completion. The app shows that retained photo in completion/history and removes only app-owned files according to the documented local lifecycle.

## Data sent for inference

A recipe request sends the selected pantry items, available equipment, requested language and recipe type, relevant preference signals, and limited recent-history context to the backend and then to Vertex AI. A cooking-step photo is sent only after an explicit gallery/camera action for advice. Do not submit personal, identifying, confidential, or sensitive imagery.

The FastAPI service validates request size and type, holds cooking-photo bytes in memory for the request, and does not write an application-owned copy of the photo, prompt, or model response. Logs are limited to operational metadata such as route, status, model role, attempt count, and latency; they exclude tokens, request bodies, pantry contents, notes, and image bytes. Firestore stores only HMAC-derived per-day quota document IDs and counters, not pantry, recipe, history, note, or photo content.

## Vertex AI, retention, and billing

The current runtime uses Vertex AI Gemini through the Cloud Run service account and [Application Default Credentials](https://docs.cloud.google.com/docs/authentication/application-default-credentials); there is no model API key in the APK. Recipe and advice routes use fixed primary/fallback models. Fallback is bounded and attempted only for named transient failures, so it is best effort rather than an availability guarantee.

Inference is billable. Per-installation and global application quotas limit calls, but they do not guarantee a specific bill, model availability, latency, or output. Google states that managed-model customer data is not used to train or fine-tune models without permission or instruction, while its current governance documentation also describes abuse-monitoring, feature-specific retention, and in-memory caching conditions. LeftOVERS therefore does not claim provider Zero Data Retention. Review the current [Google managed-model data governance documentation](https://docs.cloud.google.com/gemini-enterprise-agent-platform/resources/zero-data-retention) before publication or deployment changes.

Request/response logging is not enabled by this application's deployment code. Platform controls and service terms remain distinct from application non-persistence.

## Historical OpenAI path and `store=false`

GPT-5.6 and the OpenAI Responses API were used in the original working implementation and remain truthful historical contribution and parity context. The retained legacy client sets `store=false`, but that parameter controls Responses API application-state behavior; it is not a Vertex setting and is not, by itself, a training opt-out, data-sharing control, abuse-log exemption, or Zero Data Retention enrollment. OpenAI documents training, abuse-monitoring retention, application state, and approved retention controls separately in its current [API data controls](https://developers.openai.com/api/docs/guides/your-data). The production Vertex revision must not mount the retained OpenAI secret.

## Images and food safety

Photo guidance can describe visible state only. It cannot verify doneness, internal temperature, contamination, allergens, spoilage, or safe handling. Users must verify time, temperature, labels, allergies, and normal kitchen hygiene. A final-dish photo is local history, not a provider-generated or provider-retained asset.

## Optional synthetic sharing

No optional provider data-sharing program or synthetic-sharing project is enabled. Any future experiment requires explicit approval, a separately isolated project, and generated non-user inputs and outputs only. Real pantry, equipment, preferences, history, notes, or photos must never be routed through that experiment.
