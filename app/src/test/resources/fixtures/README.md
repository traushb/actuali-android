# Upstream encryption fixture

`encryption-key-fixture.json` is copied unchanged from [Actuali's iOS test suite](https://github.com/MattFaz/actuali/blob/main/Actuali/ActualiTests/Fixtures/encryption-key-fixture.json). Its provenance is the [upstream generator](https://github.com/MattFaz/actuali/blob/main/dev/scripts/gen-encryption-fixture.mjs).

The passwords, salt, derived key, and encrypted test message are public, synthetic test values. They do not authenticate to any server or protect a user's budget. Secret scanners can flag `derivedKeyBase64`; preserve this vector so tests continue to verify Actual's PBKDF2 and encryption conventions.
