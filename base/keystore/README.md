# The release key (optional, for your own releases)

The CI signs with the `KUROKA_KEYSTORE*` secrets (see `../../.github/workflows/android.yml`).
To sign a release build on your own machine instead, put the key here and describe it:

```
base/keystore/release.jks          # keytool -genkeypair -keystore release.jks -alias kuroka -keyalg RSA -keysize 2048 -validity 10000
base/keystore/keystore.properties  # the four lines below, never committed
```

```properties
storeFile=release.jks
storePassword=…
keyAlias=kuroka
keyPassword=…
```

Without a key, `assembleRelease` signs with the debug key so a local build still installs;
the CI stops instead, since a published release must keep the key it was first signed with.
