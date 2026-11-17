#!/usr/bin/env python3
"""Compare real SSU2/NTCP2 recovery through a controlled loopback packet link."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import tarfile
import time
import urllib.request

SCENARIOS = ('control', 'packet-loss-reordering', 'nat-port-change', 'expired-mapping', 'reconnect', 'udp-blocked')
DEPENDENCIES = (
    ('gnu.getopt', 'java-getopt', '1.0.13', 'f09dc6369ffc4fe4362cbd49b6ac6c4a7471375ac5d884c71b0a625a889c38c4'),
    ('org.gnu.gettext', 'libintl', '0.18.3', '7f3d73b7a7887da142c05722adc2701b4f93bcb9bdacdb6dcba5b62d8919f2c4'),
)
FAILURE_REASONS = {
    'recovery-deadline', 'tcp-fallback-not-observed', 'udp-session-not-retained',
    'residual-state-bound', 'peer-endpoint-not-migrated', 'connection-ids-changed-during-migration',
    'expired-mapping-not-observed', 'blocked-udp-not-observed', 'packet-faults-not-observed',
}
FAULT_SETUP_FAILURES = {
    'expired-mapping-not-observed', 'blocked-udp-not-observed', 'packet-faults-not-observed',
}
MEASUREMENTS = (
    'warmupMs', 'recoveryMs', 'faultWindowMs', 'deadlineMs', 'pendingEstablishments',
    'inboundBans', 'connectionIdEntries', 'endpointEntries', 'droppedPackets',
    'forwardedPackets', 'reorderedPackets', 'blockedUDPPackets', 'expiredMappingPackets',
    'warmupSSUVersionA', 'warmupSSUVersionB', 'recoveredSSUVersionA', 'recoveredSSUVersionB',
    'udpSessions',
)


def valid_measurement(record):
    if not isinstance(record, dict):
        return False
    if (record.get('scenario') not in SCENARIOS or type(record.get('seed')) is not int
            or type(record.get('passed')) is not bool or record.get('shutdownConfirmed') is not True
            or type(record.get('exitCode')) is not int):
        return False
    if any(type(record.get(name)) is not int or record[name] < 0 for name in MEASUREMENTS):
        return False
    if record['deadlineMs'] < 1 or record['forwardedPackets'] < 1:
        return False
    for name in ('tcpFallback', 'peerEndpointMigrated', 'connectionIdsRetained'):
        if type(record.get(name)) is not bool:
            return False
    failure = record.get('failure')
    if not isinstance(failure, str):
        return False
    reasons = set(failure.split(';')) if failure else set()
    if not reasons.issubset(FAILURE_REASONS):
        return False
    return (record['passed'] and not reasons and record['exitCode'] == 0) or (
        not record['passed'] and bool(reasons) and record['exitCode'] == 1)


def comparison_verdict(before, after):
    """Keep protocol failures visible while allowing CI to gate on new regressions."""
    baseline = {(r.get('scenario'), r.get('seed')): r for r in before}
    candidate = {(r.get('scenario'), r.get('seed')): r for r in after}
    complete = bool(baseline) and baseline.keys() == candidate.keys()
    complete = complete and len(baseline) == len(before) and len(candidate) == len(after)
    invalid = []
    for variant, records in (('baseline', before), ('candidate', after)):
        for record in records:
            setup_failed = bool(set(record.get('failure', '').split(';')) & FAULT_SETUP_FAILURES)
            if not valid_measurement(record) or setup_failed:
                invalid.append({'variant': variant, 'scenario': record.get('scenario'),
                                'seed': record.get('seed')})
    complete = complete and not invalid
    regressions = []
    for key in baseline.keys() & candidate.keys():
        old = set(filter(None, baseline[key].get('failure', '').split(';')))
        new = set(filter(None, candidate[key].get('failure', '').split(';')))
        if new - old:
            regressions.append({'scenario': key[0], 'seed': key[1],
                                'newFailures': sorted(new - old)})
    regressions.sort(key=lambda r: (r['scenario'], r['seed']))
    return {'measurementsComplete': bool(complete), 'invalidMeasurements': invalid,
            'candidatePassed': bool(after) and all(valid_measurement(r) and r['passed'] for r in after),
            'noRegressions': bool(complete) and not regressions, 'regressions': regressions}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dependency_jars(output, offline):
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
    result = []
    target_dir = output / 'dependencies'
    target_dir.mkdir()
    for group, artifact, version, expected in DEPENDENCIES:
        target = target_dir / f'{artifact}-{version}.jar'
        candidates = sorted((cache / group / artifact / version).glob('*/*.jar'))
        source = next((p for p in candidates if digest(p) == expected), None)
        if source:
            shutil.copyfile(source, target)
        elif offline:
            raise RuntimeError(f'Pinned dependency unavailable offline: {target.name}')
        else:
            url = f'https://repo.maven.apache.org/maven2/{group.replace(".", "/")}/{artifact}/{version}/{target.name}'
            with urllib.request.urlopen(url, timeout=45) as response:
                target.write_bytes(response.read(16 * 1024 * 1024 + 1))
        if digest(target) != expected:
            raise RuntimeError(f'Dependency checksum mismatch: {target.name}')
        result.append(target)
    return result


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args])


def snapshot_sources(repo, ref, source):
    """Freeze either an unchanged commit or the candidate's current Java sources."""
    source.mkdir(parents=True)
    if ref == 'WORKTREE':
        sha = git(repo, 'rev-parse', 'HEAD').decode().strip()
        for name in ('core/java/src', 'router/java/src'):
            for path in sorted((repo / name).rglob('*.java')):
                if path.is_symlink():
                    raise RuntimeError('Symlink in working source tree: ' + str(path))
                target = source / path.relative_to(repo)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
        return sha
    sha = git(repo, 'rev-parse', '--verify', ref + '^{commit}').decode().strip()
    archive = git(repo, 'archive', sha, 'core/java/src', 'router/java/src')
    with tarfile.open(fileobj=io.BytesIO(archive)) as stream:
        for entry in stream.getmembers():
            path = Path(entry.name)
            if path.is_absolute() or '..' in path.parts or entry.issym() or entry.islnk():
                raise RuntimeError('Unsafe archive member')
        stream.extractall(source, filter='data')
    return sha


def compile_ref(repo, ref, folder, java_home, jars, harness, probe_test):
    source = folder / 'source'
    sha = snapshot_sources(repo, ref, source)
    sources = sorted((source / 'router/java/src').rglob('*.java'))
    sources += [p for p in sorted((source / 'core/java/src').rglob('*.java'))
                if not str(p.relative_to(source / 'core/java/src')).startswith(('gnu/getopt/', 'gnu/gettext/'))]
    classes = folder / 'classes'
    classes.mkdir()
    argfile = folder / 'compile.args'
    # javac argfiles require quoting paths, including source checkouts with spaces.
    argfile.write_text('\n'.join('"' + str(p).replace('\\', '\\\\').replace('"', '\\"') + '"' for p in sources + [harness, probe_test]) + '\n')
    command = [str(java_home / 'bin/javac'), '-J-Xmx768m', '--release', '17', '-encoding', 'UTF-8',
               '-cp', os.pathsep.join(map(str, jars)), '-d', str(classes), '@' + str(argfile)]
    with (folder / 'compile.log').open('w') as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
    with (folder / 'probe-tests.log').open('w') as log:
        subprocess.run([str(java_home / 'bin/java'), '-cp', os.pathsep.join(map(str, [classes, *jars])),
                        'net.i2p.router.transport.udp.ProbeRetryTest'],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=20)
    provenance = {'ref': ref, 'commit': sha, 'workingTree': ref == 'WORKTREE', 'javaSources': len(sources),
                  'sourceTreeSha256': hashlib.sha256(b''.join(str(p.relative_to(source)).encode() + b'\0' + p.read_bytes() for p in sources)).hexdigest(),
                  'harnessSha256': digest(harness), 'probeTestSha256': digest(probe_test),
                  'probeTestsPassed': True, 'dependencySha256': {p.name: digest(p) for p in jars}}
    return classes, provenance


def run_process(command, folder, timeout=180):
    """Own the controller and both router JVMs, including on timeout/interruption."""
    with (folder / 'process.log').open('w') as log:
        process = subprocess.Popen(command, cwd=folder, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        finally:
            # The controller may have exited while one child JVM is still alive.
            # Terminate the whole session rather than leaving test routers behind.
            try:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                finally:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
            except ProcessLookupError:
                pass
            process.wait()


def parse_result(lines, scenario, seed, status):
    records = [json.loads(line[len('RECOVERY_RESULT '):]) for line in lines
               if line.startswith('RECOVERY_RESULT ')]
    if len(records) != 1 or not isinstance(records[0], dict):
        return {'scenario': scenario, 'seed': seed, 'passed': False,
                'reason': 'Expected exactly one result; inspect process.log', 'exitCode': status}
    result = records[0]
    result['exitCode'] = status
    valid = (result.get('scenario') == scenario and type(result.get('seed')) is int
             and result['seed'] == seed and valid_measurement(result))
    result['passed'] = result.get('passed') is True and valid and status == 0
    if not valid:
        result['reason'] = 'Result identity, measurements or shutdown confirmation is invalid'
    return result


def run_scenario(classes, jars, java_home, output, scenario, seed):
    folder = output / f'{scenario}-{seed}'
    folder.mkdir()
    command = [str(java_home / 'bin/java'), '-Xmx512m', '-cp', os.pathsep.join(map(str, [classes, *jars])),
               'net.i2p.router.transport.udp.TransportRecoveryLab', str(folder / 'runtime'), scenario, str(seed)]
    started = time.monotonic()
    try:
        status = run_process(command, folder)
        lines = (folder / 'process.log').read_text(errors='replace').splitlines()
        result = parse_result(lines, scenario, seed, status)
    except subprocess.TimeoutExpired:
        result = {'scenario': scenario, 'seed': seed, 'passed': False, 'reason': 'Process deadline exceeded', 'exitCode': 124}
    except (ValueError, OSError) as error:
        result = {'scenario': scenario, 'seed': seed, 'passed': False, 'reason': str(error), 'exitCode': 1}
    result['wallSeconds'] = round(time.monotonic() - started, 3)
    (folder / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-ref', required=True, help='Unchanged upstream commit available in local git')
    parser.add_argument('--baseline-repo', type=Path, help='Local repository containing the baseline ref (default: this checkout)')
    parser.add_argument('--candidate-ref', default='WORKTREE', help='Candidate commit, or WORKTREE to snapshot current sources (default)')
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--output', type=Path, required=True, help='New output directory; holds disposable router keys and private logs')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--gate', choices=('candidate', 'regressions'), default='candidate',
                        help='Require every candidate case to pass (default), or fail CI on new failures relative to the baseline')
    parser.add_argument('--scenarios', nargs='+', choices=SCENARIOS, default=list(SCENARIOS))
    parser.add_argument('--seeds', nargs='+', type=int, default=[1, 2])
    args = parser.parse_args()
    if args.baseline_ref == 'WORKTREE':
        parser.error('baseline must be an unchanged Git commit')
    if len(set(args.seeds)) != len(args.seeds) or len(set(args.scenarios)) != len(args.scenarios):
        parser.error('seeds and scenarios must not contain duplicates')
    repo = Path(__file__).resolve().parents[2]
    java_home = args.java_home
    if not java_home:
        binary = shutil.which('javac')
        if not binary:
            parser.error('JDK 17 or later is required')
        java_home = Path(binary).resolve().parents[1]
    output = args.output.resolve()
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    jars = dependency_jars(output, args.offline)
    harness = output / 'TransportRecoveryLab.java'
    shutil.copyfile(Path(__file__).with_name('TransportRecoveryLab.java'), harness)
    probe_test = output / 'ProbeRetryTest.java'
    shutil.copyfile(Path(__file__).with_name('ProbeRetryTest.java'), probe_test)
    report = {'baselineRef': args.baseline_ref, 'candidateRef': args.candidate_ref,
              'runnerSha256': digest(Path(__file__)), 'harnessSha256': digest(harness),
              'probeTestSha256': digest(probe_test),
              'specification': 'https://i2p.net/en/docs/specs/ssu2/',
              'scope': 'Real encrypted transport sessions on network 222 using loopback-only addresses and dummy overlay services; no public I2P peers or tunnel performance claims', 'variants': {}}
    for label, ref in [('baseline', args.baseline_ref), ('candidate', args.candidate_ref)]:
        folder = output / label
        folder.mkdir()
        source_repo = args.baseline_repo.resolve() if label == 'baseline' and args.baseline_repo else repo
        classes, provenance = compile_ref(source_repo, ref, folder, java_home, jars, harness, probe_test)
        records = []
        for seed in args.seeds:
            for scenario in args.scenarios:
                record = run_scenario(classes, jars, java_home, folder, scenario, seed)
                records.append(record)
                print(json.dumps({'variant': label, **record}), flush=True)
        report['variants'][label] = {'source': provenance, 'runs': records}
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    report.update(comparison_verdict(report['variants']['baseline']['runs'],
                                     report['variants']['candidate']['runs']))
    report['gate'] = args.gate
    report['passed'] = report['measurementsComplete'] and (
        report['candidatePassed'] if args.gate == 'candidate' else report['noRegressions'])
    report['comparison'] = []
    baseline = {(r['scenario'], r['seed']): r for r in report['variants']['baseline']['runs']}
    for current in report['variants']['candidate']['runs']:
        before = baseline[(current['scenario'], current['seed'])]
        report['comparison'].append({'scenario': current['scenario'], 'seed': current['seed'],
            'baselinePassed': before['passed'], 'candidatePassed': current['passed'],
            'baselineRecoveryMs': before.get('recoveryMs'), 'candidateRecoveryMs': current.get('recoveryMs'),
            'baselinePendingEstablishments': before.get('pendingEstablishments'),
            'candidatePendingEstablishments': current.get('pendingEstablishments')})
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
