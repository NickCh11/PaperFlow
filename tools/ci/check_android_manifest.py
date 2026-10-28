"""Enforce the release security boundary on AGP's merged manifest, including dependencies."""
import argparse
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "com.example.myapplication"
PERMISSIONS = {
    "android.permission.CAMERA", "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.USE_BIOMETRIC", "android.permission.USE_FINGERPRINT",
    "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
    PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}


def check(path):
    root = ET.parse(path).getroot()
    if root.get("package") != PACKAGE or root.get(ANDROID + "sharedUserId"):
        raise ValueError("Unexpected release package or shared UID")
    apps = root.findall("application")
    sdk = root.find("uses-sdk")
    if len(apps) != 1 or sdk is None or int(sdk.get(ANDROID + "targetSdkVersion", "0")) < 28:
        raise ValueError("Missing application or safe target SDK")
    app = apps[0]
    for flag in ("debuggable", "testOnly", "usesCleartextTraffic"):
        if app.get(ANDROID + flag, "false") != "false":
            raise ValueError(f"Unsafe release application flag: {flag}")
    if app.get(ANDROID + "networkSecurityConfig"):
        raise ValueError("A new network security configuration requires explicit policy review")
    for permission in root.findall("uses-permission") + root.findall("uses-permission-sdk-23"):
        name = permission.get(ANDROID + "name")
        if name not in PERMISSIONS:
            raise ValueError(f"Unexpected release permission: {name}")
        if name == "android.permission.WRITE_EXTERNAL_STORAGE" and permission.get(ANDROID + "maxSdkVersion") != "28":
            raise ValueError("Legacy storage permission must end at API 28")
    definitions = root.findall("permission")
    for permission in definitions:
        if permission.get(ANDROID + "protectionLevel") != "signature":
            raise ValueError("App-defined permissions must be signature protected")
    public_entries = []
    file_providers = []
    for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
        for component in app.findall(kind):
            name = component.get(ANDROID + "name", "")
            full_name = PACKAGE + name if name.startswith(".") else name
            if "TranslationTestDocumentsProvider" in name or "translation-test-documents" in component.get(ANDROID + "authorities", ""):
                raise ValueError("Debug document provider must never ship in release")
            exported = component.get(ANDROID + "exported")
            if exported not in (None, "true", "false"):
                raise ValueError("Exported state must be explicit and reviewable")
            if exported is None and component.find("intent-filter") is not None:
                raise ValueError("Components with intent filters require explicit exported state")
            if exported == "true":
                if kind == "activity" and full_name == PACKAGE + ".DocumentEntryActivity":
                    public_entries.append(component)
                elif not (kind == "receiver" and name == "androidx.profileinstaller.ProfileInstallReceiver"
                          and component.get(ANDROID + "permission") == "android.permission.DUMP"):
                    raise ValueError(f"Unexpected exported {kind}: {name}")
            if kind == "provider" and name == "androidx.core.content.FileProvider":
                file_providers.append(component)
    if len(public_entries) != 1:
        raise ValueError("Exactly one sanitized public document entry is required")
    if len(file_providers) != 1:
        raise ValueError("Exactly one private file provider is required")
    provider = file_providers[0]
    if (provider.get(ANDROID + "exported") != "false" or
            provider.get(ANDROID + "grantUriPermissions") != "true" or
            provider.get(ANDROID + "authorities") != PACKAGE + ".files"):
        raise ValueError("File sharing must use private, explicit URI grants")
    roots = [item for item in provider.findall("meta-data")
             if item.get(ANDROID + "name") == "android.support.FILE_PROVIDER_PATHS"]
    if len(roots) != 1 or roots[0].get(ANDROID + "resource") != "@xml/file_paths":
        raise ValueError("File sharing must use the reviewed export-folder roots")
    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest")
    args = parser.parse_args()
    try:
        check(args.manifest)
        print("Merged release manifest security policy passed.")
    except (ValueError, OSError, ET.ParseError) as error:
        parser.exit(1, str(error) + "\n")
