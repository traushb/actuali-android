# Privacy

Actuali for Android is an unofficial, community-maintained port. It is not affiliated with Actual Budget or the original Actuali author.

## Budgets and connections

Budgets and transaction history are stored locally on your device. Synchronization sends budget data to the Actual Budget server you configure. If you enable end-to-end encryption for a budget, the app uses Actual's encrypted synchronization protocol. Android system backup of the application's data is disabled.

Sign-in can open your server's configured OpenID identity provider in a browser. Bank sync can contact the configured provider through your Actual server, or a SimpleFIN endpoint if you set up a direct SimpleFIN connection. External support and privacy links open GitHub in your browser. Those services have their own privacy policies.

## Credentials

The app stores credentials locally using its Android Keystore-backed secret store. Connection settings, including server addresses and custom headers, are retained on the device. Never share credentials, headers, budget exports, or screenshots containing private information in public issues.

## Optional location and imports

If you grant location permission and enable location recording, saved payee locations support nearby-payee suggestions. You can view and clear recorded locations in the app. Sharing a bank message with the app can create a local pending transaction import; review its contents before approving it.

## Diagnostics and tracking

The app has no analytics or advertising SDK. Diagnostic reports are generated locally; sharing or copying a report requires an explicit action in the app. Review any report before posting it publicly. Local budget backups and exported files should also be treated as private financial data.

The built-in demo budget contains fictional sample data and can be used without connecting a server.

The More overview card does not display your server address. Connection & Data settings still show connection details so you can configure and manage your server.

## Questions

Use [this Android repository's issues](https://github.com/traushb/actuali-android/issues) for questions. Keep public reports free of personal data.
