"""Linux x86_64 cloud bootstrap. Verifies official artifacts; preserves source files."""
import hashlib
import os
import pathlib
import platform
import shutil
import subprocess
import tarfile
import urllib.parse
import zipfile

assert platform.system() == "Linux" and platform.machine() == "x86_64", "Use Android Studio on other platforms"
root = pathlib.Path(os.environ.get("CUNQI_TOOLCHAINS", "/workspace/toolchains"))
root.mkdir(parents=True, exist_ok=True)
cache = root / "downloads"
cache.mkdir(exist_ok=True)

def fetch(url, name):
    file = cache / name
    if not file.exists():
        temp = file.with_suffix(file.suffix + ".part")
        subprocess.run(["curl", "-fsSL", "--retry", "2", url, "-o", str(temp)], check=True)
        temp.replace(file)
    return file

def verified(url, name, algorithm, expected):
    file = fetch(url, name)
    digest = hashlib.new(algorithm)
    with file.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""): digest.update(chunk)
    if digest.hexdigest() != expected:
        raise RuntimeError(f"Checksum mismatch for {name}; remove cached archive and retry from official source")
    return file

jdk_url = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.16%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz"
jdk_sum = fetch(jdk_url + ".sha256.txt", "jdk.sha256").read_text().split()[0]
jdk = verified(jdk_url, "jdk.tar.gz", "sha256", jdk_sum)
if not (root / "jdk-17.0.16+8/bin/javac").exists():
    with tarfile.open(jdk) as archive: archive.extractall(root, filter="data")

gradle_url = "https://services.gradle.org/distributions/gradle-9.6.0-bin.zip"
gradle_sum = fetch(gradle_url + ".sha256", "gradle9.sha256").read_text().strip()
gradle = verified(gradle_url, "gradle9.zip", "sha256", gradle_sum)
if not (root / "gradle-9.6.0/bin/gradle").exists():
    with zipfile.ZipFile(gradle) as archive: archive.extractall(root)
(root / "gradle-9.6.0/bin/gradle").chmod(0o755)

sdk = root / "android-sdk"
sdk.mkdir(exist_ok=True)
tools = verified("https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip",
    "android-tools23.zip", "sha1", "e025545c62a8e64c7559119566a569fb1dec5f60")
dest = sdk / "cmdline-tools/23.0"
if not dest.exists():
    temp = cache / "commandline-unpack"
    with zipfile.ZipFile(tools) as archive: archive.extractall(temp)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(temp / "cmdline-tools"), str(dest))
for file in (dest / "bin").iterdir(): file.chmod(0o755)

# Add the platform's public CA to a private Java trust store, retaining all normal roots.
trust = root / "cacerts"
if not trust.exists(): shutil.copyfile(root / "jdk-17.0.16+8/lib/security/cacerts", trust)
cert = pathlib.Path("/usr/local/share/ca-certificates/environment-proxy-ca.crt")
if cert.exists():
    keytool = str(root / "jdk-17.0.16+8/bin/keytool")
    args = [keytool, "-keystore", str(trust), "-storepass", "changeit", "-alias", "cloud-proxy"]
    found = subprocess.run(args + ["-list"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not found:
        subprocess.run(args + ["-importcert", "-noprompt", "-file", str(cert)], check=True, stdout=subprocess.DEVNULL)

user = pathlib.Path(os.environ.get("CUNQI_ANDROID_USER_HOME", "/workspace/.android"))
user.mkdir(parents=True, exist_ok=True)
gradle_user = pathlib.Path(os.environ.get("CUNQI_GRADLE_USER_HOME", "/workspace/.gradle"))
gradle_user.mkdir(parents=True, exist_ok=True)
proxy = urllib.parse.urlparse(os.environ.get("HTTPS_PROXY", ""))
props = {"systemProp.javax.net.ssl.trustStore": str(trust)}
if proxy.hostname:
    for protocol in ["http", "https"]:
        props[f"systemProp.{protocol}.proxyHost"] = proxy.hostname
        props[f"systemProp.{protocol}.proxyPort"] = str(proxy.port or 8080)
    props["systemProp.http.nonProxyHosts"] = "localhost|127.*"

def update_properties(file, values):
    lines = file.read_text().splitlines() if file.exists() else []
    lines = [line for line in lines if line.split("=", 1)[0] not in values]
    file.write_text("\n".join(lines + [key + "=" + value for key, value in values.items()]) + "\n")

update_properties(gradle_user / "gradle.properties", props)
repo = pathlib.Path(__file__).resolve().parent.parent
update_properties(repo / "local.properties", {"sdk.dir": str(sdk)})
print("Verified JDK, Gradle and Android tools; local configuration prepared.")
