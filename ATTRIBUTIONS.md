# Third-Party Attributions & Open Source Licenses

Sovereign is built on top of high-quality open-source software, fonts, and open-weight machine learning architectures. We gratefully acknowledge the following upstream authors and contributors.

---

## 1. Core On-Device Speech & LLM Engines

### sherpa-onnx
- **Description:** Real-time on-device speech-to-text inference engine powered by ONNX Runtime.
- **Copyright:** Copyright (c) 2022–2026 Next-gen Kaldi / Xiaomi Corporation (Daniel Povey, Fangjun Kuang, and contributors).
- **License:** Apache License 2.0
- **Homepage:** [https://github.com/k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)

### Google LiteRT / LiteRT-LM
- **Description:** On-device hardware-accelerated Large Language Model runtime (OpenCL GPU & CPU).
- **Copyright:** Copyright (c) Google LLC.
- **License:** Apache License 2.0
- **Homepage:** [https://github.com/google-ai-edge/LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)

---

## 2. Typography & Fonts

### Inter Font Family
- **Description:** Variable geometric sans-serif typeface designed for computer screens.
- **Copyright:** Copyright (c) 2016–2024 The Inter Project Authors ([https://github.com/rsms/inter](https://github.com/rsms/inter))
- **License:** SIL Open Font License, Version 1.1
- **Homepage:** [https://rsms.me/inter/](https://rsms.me/inter/)

---

## 3. Android Platform & Standard Frameworks

### AndroidX & Jetpack Compose
- **Description:** Modern Android UI toolkit, lifecycle, and runtime libraries.
- **Copyright:** Copyright (c) The Android Open Source Project / Google LLC.
- **License:** Apache License 2.0
- **Homepage:** [https://developer.android.com/jetpack](https://developer.android.com/jetpack)

### Kotlin, Coroutines & Serialization
- **Description:** Programming language, asynchronous coroutine primitives, and JSON serialization.
- **Copyright:** Copyright (c) 2010–2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
- **License:** Apache License 2.0
- **Homepage:** [https://kotlinlang.org/](https://kotlinlang.org/)

---

## 4. Networking, Cryptography & UI Libraries

### Java-WebSocket
- **Description:** WebSocket client and server implementation, used for the phone ↔ desktop transport.
- **Copyright:** Copyright (c) 2010–2020 Nathan Rajlich and contributors.
- **License:** MIT License
- **Homepage:** [https://github.com/TooTallNate/Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket)

### Bouncy Castle
- **Description:** Cryptographic APIs; signs and validates the phone server's self-signed TLS certificate.
- **Copyright:** Copyright (c) The Legion of the Bouncy Castle Inc.
- **License:** Bouncy Castle Licence (MIT-style)
- **Homepage:** [https://www.bouncycastle.org/](https://www.bouncycastle.org/)

### MaterialKolor
- **Description:** Material You dynamic colour generation, used for the brand palette.
- **Copyright:** Copyright (c) the MaterialKolor authors and contributors.
- **License:** MIT License
- **Homepage:** [https://github.com/jordond/materialkolor](https://github.com/jordond/materialkolor)

---

## 5. Supported Open-Weight Models (Downloaded on Demand)

Sovereign does not bundle or redistribute model weights within the application package. Users may download compatible pre-trained model weights on demand directly to their device under the respective author licenses:

- **NVIDIA Nemotron & Parakeet (FastConformer):** Developed by NVIDIA Corporation under the NVIDIA Open Model License / CC-BY-4.0 ([https://github.com/NVIDIA/NeMo](https://github.com/NVIDIA/NeMo)).
- **OpenAI Whisper:** Developed by OpenAI under the MIT License ([https://github.com/openai/whisper](https://github.com/openai/whisper)).
- **Google Gemma Models (Gemma 3 & 4):** Developed by Google LLC under the [Gemma Terms of Use](https://ai.google.dev/gemma/terms).
