# Third-party notices and combined licensing

This branch distributes the combined Kaixinguo application under **GPL-3.0-only**. The full GPL-3.0 text is in [LICENSE](LICENSE). This notice does not attempt to relicense third-party code.

- Original Kaixinguo sources: Copyright (c) 2026 Kada Liao, MIT License. Retained in [LICENSE-MIT-ORIGINAL](LICENSE-MIT-ORIGINAL). Their original attribution and MIT grants remain intact.
- PhoneLogin.kt: sourced from [yagay/hongguo-android](https://github.com/yagay/hongguo-android), `main`, GPL-3.0-only; its source credits [tsukiyo-zero/hongguoTV](https://github.com/tsukiyo-zero/hongguoTV) and [charlie901208/HGTV](https://github.com/charlie901208/HGTV).
- Signer.kt, AesV3.kt, Bytes.kt, VendorConstants.kt, ProtocolTransport.kt: sourced from `yagay/hongguo-android`, `main`, GPL-3.0-only, with original attribution comments preserved. Signer.kt also credits `drpys` as noted in its upstream file.
- Other app source added on this branch is original integration work; when built together with the GPL sources, the complete distributed combined program must satisfy GPL-3.0-only.

The signer and SMS endpoints are unofficial third-party integrations, not endorsed by the service provider. External account/device validation and server-side protection are not overridden. Do not ship secret credentials or user-specific cookies in any source or debug logs. Verify account login on-device before claiming it works.

This project continues to use the existing Media3 and openhongguo video/playback stack. No rewrite of the existing player or UI is required.
