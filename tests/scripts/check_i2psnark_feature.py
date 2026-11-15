#!/usr/bin/env python3
"""Exercise one independently reviewable Snark feature after building dependency jars."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CASES = {
    "discovery": (("DHTDiscoveryRegression", "org.klomp.snark.dht."), ("SnarkDiscoveryRegression", "org.klomp.snark.")),
    "lifecycle": (("SnarkLifecycleRegression", "org.klomp.snark."),),
    "notifications": (("SnarkNotificationRegression", "org.klomp.snark."),),
    "handoff": (("SnarkHandoffRegression", "org.klomp.snark."),),
    "catalog": (("PeerCatalogRegression", "org.klomp.snark."), ("PeerCatalogWebRegression", "org.klomp.snark.")),
    "frames": (("PeerFrameRegression", "org.klomp.snark."),),
    "tracker": (("TrackerPeerRegression", "org.klomp.snark."),),
    "nonces": (("SnarkNonceRegression", "org.klomp.snark.web."),),
    "downloads": (("SnarkDownloadRegression", "org.klomp.snark.web."),),
    "ranges": (("SnarkRangeRegression", "org.klomp.snark.web."),),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case", choices=CASES)
    args = parser.parse_args()
    jars = ("core/java/build/i2p.jar", "apps/ministreaming/java/build/mstreaming.jar",
            "apps/systray/java/build/systray.jar", "apps/desktopgui/dist/desktopgui.jar", "apps/jetty/jettylib/*")
    classpath = os.pathsep.join(str(ROOT / jar) for jar in jars)
    if args.case == "catalog":
        classpath += os.pathsep + str(ROOT / "apps/routerconsole/java/build/routerconsole.jar")
    sources = sorted((ROOT / "apps/i2psnark/java/src").rglob("*.java"))
    fixtures = CASES[args.case]
    sources += [ROOT / "tests/regression" / (name + ".java") for name, _ in fixtures]
    if args.case in ("lifecycle", "notifications", "handoff"):
        sources.append(ROOT / "tests/regression/SnarkIntegrationSupport.java")
    if args.case == "handoff":
        sources.append(ROOT / "apps/routerconsole/java/src/net/i2p/router/web/helpers/TorrentHelper.java")
    with tempfile.TemporaryDirectory(prefix="snark-" + args.case + "-") as temporary:
        work = Path(temporary)
        arguments = work / "sources.args"
        arguments.write_text("\n".join('"' + str(path) + '"' for path in sources) + "\n")
        classes = work / "classes"
        subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(classes), "@" + str(arguments)], check=True, timeout=120)
        for name, package in fixtures:
            subprocess.run(["java", "-Xmx128m", "-Djbigi.enable=false", "-Djcpuid.enable=false", "-Djbigi.dontLog=true",
                            "-DI2P_DISABLE_OUTPUT_OVERRIDE=true", "-cp", os.pathsep.join((str(classes), classpath)),
                            package + name], cwd=work, check=True, timeout=30)
        if args.case == "catalog":
            subprocess.run(["node", "--check", str(ROOT / "apps/i2psnark/resources/js/catalog.js")], check=True, timeout=15)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
