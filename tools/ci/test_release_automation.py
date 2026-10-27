import base64
import copy
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release_android as release
from check_apk import ARCHITECTURES, REQUIRED, check_directory


class ReleaseAutomationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.event = self.root / 'event.json'
        self.payload = dict(action='published', release=dict(id=42, tag_name='v1.2.2', draft=False, prerelease=False))
        self.event.write_text(json.dumps(self.payload))
        self.env = patch.dict(os.environ, dict(GITHUB_EVENT_NAME='release', GITHUB_EVENT_PATH=str(self.event),
                              GITHUB_SHA='a' * 40, GITHUB_REF='refs/tags/v1.2.2', GH_REPO='owner/repo'))
        self.env.start(); self.addCleanup(self.env.stop)
        self.state = release.event_state()
        self.jobs = [dict(name=n, conclusion='success') for n in
                     ('Android compilation and native checks', 'PR gate', 'Device tests API 29', 'Device tests API 37.0',
                      'Unit tests, lint and translations', 'sonar / SonarQube analysis',
                      'language-runtime / native (arm64-v8a)', 'language-runtime / native (armeabi-v7a)',
                      'language-runtime / native (x86_64)')]

    def bundle(self):
        manifest = dict(self.state, run=91, certificate='c' * 64)
        for name in release.apk_names(self.state) | {'SHA256SUMS.txt'}:
            (self.root / name).write_bytes(('signed-fixture-' + name).encode())
        manifest['files'] = {p.name: release.file_record(p) for p in self.root.iterdir() if p.name != 'event.json'}
        (self.root / release.MANIFEST).write_text(json.dumps(manifest))
        return manifest

    def assets(self, manifest):
        return [dict(id=i, name=name, state='uploaded', size=record['size'], digest='sha256:' + record['sha256'])
                for i, (name, record) in enumerate(manifest['files'].items(), 1)] + [dict(id=9, name=release.MANIFEST)]

    def test_semantic_versions_increase_and_preserve_existing_android_mapping(self):
        codes = [release.parse_version(v)[1] for v in ('1.0.0', '1.1.0', '1.2.2', '2.0.0')]
        self.assertEqual(sorted(codes), codes)
        self.assertEqual(1002002, release.parse_version('1.2.2')[1])
        for value in ('01.0.0', '1.01.0', '1.0.01', '1.0', '1.0.0-beta', '1.1000.0', '2100.0.1', '0.0.0', '1.0.0\n'):
            with self.subTest(value=value), self.assertRaises(ValueError): release.parse_version(value)

    def test_only_a_published_stable_release_can_build(self):
        for event in ('push', 'pull_request', 'workflow_dispatch', 'workflow_run'):
            with patch.dict(os.environ, GITHUB_EVENT_NAME=event), self.assertRaises(ValueError): release.event_state()
        for change in (dict(action='created'), dict(action='edited'), dict(release=dict(self.payload['release'], draft=True)),
                       dict(release=dict(self.payload['release'], prerelease=True)), dict(release=dict(self.payload['release'], tag_name='v1.2.2;echo bad'))):
            payload = dict(self.payload, **change); self.event.write_text(json.dumps(payload))
            with self.subTest(change=change), self.assertRaises(ValueError): release.event_state()

    def test_tag_ref_and_sha_must_be_valid(self):
        for change in (dict(GITHUB_REF='refs/heads/main'), dict(GITHUB_SHA='not-a-commit')):
            with patch.dict(os.environ, **change), self.assertRaises(ValueError): release.event_state()

    def test_annotated_tags_are_resolved_to_the_commit(self):
        with patch.object(release, 'api', side_effect=[{'object': {'type': 'tag', 'sha': 'b' * 40}}, {'object': {'type': 'commit', 'sha': 'a' * 40}}]):
            self.assertEqual('a' * 40, release.remote_tag_sha('v1.2.2'))

    def test_moved_tag_or_wrong_checkout_is_rejected(self):
        for checkout, remote in (('a' * 40, 'b' * 40), ('b' * 40, 'a' * 40)):
            with patch.object(release, 'run', return_value=checkout), patch.object(release, 'remote_tag_sha', return_value=remote), self.assertRaises(ValueError):
                release.validate_source(self.state)

    def test_commit_outside_main_is_rejected(self):
        with patch.object(release, 'run', return_value='a' * 40), patch.object(release, 'remote_tag_sha', return_value='a' * 40), patch.object(release.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'git')), self.assertRaises(subprocess.CalledProcessError):
            release.validate_source(self.state)

    def test_immutable_or_changed_release_is_rejected(self):
        for changed in (dict(tag_name='v2.0.0'), dict(draft=True), dict(prerelease=True), dict(immutable=True)):
            current = dict(self.payload['release'], **changed)
            with patch.object(release, 'run', return_value='a' * 40), patch.object(release, 'remote_tag_sha', return_value='a' * 40), patch.object(release.subprocess, 'run'), patch.object(release, 'api', return_value=current), self.assertRaises(ValueError):
                release.validate_source(self.state)

    def test_prepare_uses_successful_full_main_ci_and_ignores_pr_or_other_commits(self):
        good = dict(head_sha='a' * 40, head_branch='main', status='completed', conclusion='success', event='push', id=91, run_number=1)
        runs = [dict(good, head_branch='work', run_number=3), dict(good, head_sha='b' * 40, run_number=2), good]
        state_file = self.root / 'state.json'
        with patch.object(release, 'STATE', state_file), patch.object(release, 'validate_source'), patch.object(release, 'release_assets', return_value=[]), patch.object(release, 'version', return_value=('1.2.1', 1002001)), patch.object(release, 'pages', side_effect=[[[]], [{'workflow_runs': runs[:2]}, {'workflow_runs': runs[2:]}], [{'jobs': self.jobs[:4]}, {'jobs': self.jobs[4:]}]]):
            release.prepare()
        self.assertEqual(91, json.loads(state_file.read_text())['run'])

    def test_missing_ci_or_non_increasing_versions_fail_before_build(self):
        for previous in ([], [dict(id=30, draft=False, tag_name='v1.2.3')]):
            with patch.object(release, 'validate_source'), patch.object(release, 'release_assets', return_value=[]), patch.object(release, 'version', return_value=('1.2.1', 1002001)), patch.object(release, 'pages', side_effect=[[previous], [{'workflow_runs': []}]]), self.assertRaises((ValueError, RuntimeError)):
                release.prepare()
        with patch.object(release, 'validate_source'), patch.object(release, 'release_assets', return_value=[]), patch.object(release, 'version', return_value=('1.2.2', 1002002)), self.assertRaises(ValueError): release.prepare()

    def test_application_id_and_versions_are_checked_before_signing(self):
        good = "package: name='com.example.myapplication' versionCode='1002002' versionName='1.2.2'"
        release.validate_badging(good, self.state)
        for bad in (good.replace('1002002', '1002001'), good.replace('1.2.2', '1.2.1'), good.replace('com.example.myapplication', 'other.app')):
            with self.assertRaises(ValueError): release.validate_badging(bad, self.state)

    def test_existing_complete_matching_assets_are_a_noop(self):
        manifest = self.bundle()
        with patch.object(release, 'run', return_value=json.dumps(manifest)):
            self.assertTrue(release.already_complete(self.state, self.assets(manifest)))
        self.assertFalse(release.already_complete(self.state, []))

    def test_partial_duplicate_changed_or_wrong_commit_assets_are_never_overwritten(self):
        manifest = self.bundle(); assets = self.assets(manifest)
        bad_digest = copy.deepcopy(assets); bad_digest[0]['digest'] = 'sha256:' + '0' * 64
        for invalid in (assets[:1], assets + [assets[0]], bad_digest):
            with patch.object(release, 'run', return_value=json.dumps(manifest)), self.assertRaises(ValueError):
                release.already_complete(self.state, invalid)
        manifest['sha'] = 'b' * 40
        with patch.object(release, 'run', return_value=json.dumps(manifest)), self.assertRaises(ValueError): release.already_complete(self.state, assets)

    def test_bundle_requires_three_apks_valid_identity_and_digests(self):
        manifest = self.bundle()
        for key, value in (('certificate', ''), ('code', 1), ('run', None), ('files', {})):
            with self.subTest(key=key), self.assertRaises(ValueError): release.validate_manifest(dict(manifest, **{key: value}), self.state)

    def test_changed_bundle_bytes_abort_before_any_upload(self):
        manifest = self.bundle(); (self.root / 'event.json').unlink()
        (self.root / next(iter(release.apk_names(self.state)))).write_bytes(b'unsigned replacement')
        with patch.object(release, 'DIST', self.root), patch.object(release, 'event_state', return_value=self.state), patch.object(release, 'run') as command, self.assertRaises(ValueError): release.publish()
        command.assert_not_called()

    def test_upload_failure_only_cleans_acknowledged_asset_ids(self):
        self.bundle(); (self.root / 'event.json').unlink()
        calls = []
        def command(*args):
            calls.append(args)
            if 'DELETE' in args:return ''
            path = Path(args[-1]); record = release.file_record(path)
            if len([c for c in calls if 'POST' in c]) == 2:raise subprocess.CalledProcessError(1, 'upload')
            return json.dumps(dict(id=71, state='uploaded', digest='sha256:' + record['sha256']))
        with patch.object(release, 'DIST', self.root), patch.object(release, 'event_state', return_value=self.state), patch.object(release, 'validate_source'), patch.object(release, 'require_newer_version'), patch.object(release, 'release_assets', return_value=[]), patch.object(release, 'run', side_effect=command), self.assertRaises(subprocess.CalledProcessError): release.publish()
        deletes = [c for c in calls if 'DELETE' in c]
        self.assertEqual(1, len(deletes)); self.assertTrue(deletes[0][-1].endswith('/71'))
        self.assertTrue(all('--clobber' not in c for c in calls))

    def test_successful_upload_uses_release_id_and_manifest_last(self):
        manifest = self.bundle(); (self.root / 'event.json').unlink(); names = []
        def command(*args):
            if 'POST' in args:
                self.assertIn('/releases/42/assets?', args[4]); path = Path(args[-1]); names.append(path.name)
                return json.dumps(dict(id=len(names), state='uploaded', digest='sha256:' + release.file_record(path)['sha256']))
            return json.dumps(manifest)
        with patch.object(release, 'DIST', self.root), patch.object(release, 'event_state', return_value=self.state), patch.object(release, 'validate_source'), patch.object(release, 'require_newer_version'), patch.object(release, 'release_assets', side_effect=[[], self.assets(manifest)]), patch.object(release, 'run', side_effect=command): release.publish()
        self.assertEqual(release.MANIFEST, names[-1]); self.assertEqual(release.expected_names(self.state), set(names))

    def test_signing_missing_credentials_fails_without_generating_a_key(self):
        state_file = self.root / 'state.json'; state_file.write_text(json.dumps(dict(self.state, run=91)))
        with patch.object(release, 'STATE', state_file), patch.dict(os.environ, PAPERFLOW_KEYSTORE_BASE64=''), patch.object(release.subprocess, 'run') as command, self.assertRaises(RuntimeError): release.sign()
        command.assert_not_called()

    def test_signing_keeps_secrets_out_of_arguments_and_removes_temporary_key(self):
        state_file = self.root / 'state.json'; state_file.write_text(json.dumps(dict(self.state, run=91)))
        dist = self.root / 'dist'; commands = []; keys = []
        def process(args, **kwargs):
            commands.append(args)
            if 'sign' in args:
                key = Path(args[args.index('--ks') + 1]); keys.append(key)
                self.assertEqual(b'existing-key-fixture', key.read_bytes())
                self.assertIn('env:PAPERFLOW_STORE_PASSWORD', args)
                self.assertIn('env:PAPERFLOW_KEY_PASSWORD', args)
                Path(args[args.index('--out') + 1]).write_bytes(b'signed-test-apk')
        badging = "package: name='com.example.myapplication' versionCode='1002002' versionName='1.2.2'"
        def output(*args):
            return badging if 'badging' in args else 'Signer #1 certificate SHA-256 digest: ' + 'c' * 64
        env = dict(ANDROID_HOME=str(self.root), RUNNER_TEMP=str(self.root), PAPERFLOW_KEYSTORE_BASE64=base64.b64encode(b'existing-key-fixture').decode(),
                   PAPERFLOW_STORE_PASSWORD='store-fixture-password', PAPERFLOW_KEY_ALIAS='existing-entry', PAPERFLOW_KEY_PASSWORD='key-fixture-password')
        with patch.object(release, 'ROOT', self.root), patch.object(release, 'STATE', state_file), patch.object(release, 'DIST', dist), patch.dict(os.environ, env), patch.object(release.subprocess, 'run', side_effect=process), patch.object(release, 'run', side_effect=output):
            release.sign()
        self.assertEqual(3, len(keys)); self.assertTrue(all(not key.exists() for key in keys))
        self.assertTrue((dist / release.MANIFEST).is_file())
        self.assertTrue(all('store-fixture-password' not in args and 'key-fixture-password' not in args for args in commands))

    def test_signature_failure_never_creates_a_completion_manifest(self):
        state_file = self.root / 'state.json'; state_file.write_text(json.dumps(dict(self.state, run=91)))
        dist = self.root / 'dist'
        env = dict(ANDROID_HOME=str(self.root), RUNNER_TEMP=str(self.root), PAPERFLOW_KEYSTORE_BASE64=base64.b64encode(b'existing-key').decode(), PAPERFLOW_STORE_PASSWORD='fixture', PAPERFLOW_KEY_ALIAS='entry', PAPERFLOW_KEY_PASSWORD='fixture')
        badging = "package: name='com.example.myapplication' versionCode='1002002' versionName='1.2.2'"
        with patch.object(release, 'STATE', state_file), patch.object(release, 'DIST', dist), patch.dict(os.environ, env), patch.object(release.subprocess, 'run'), patch.object(release, 'run', side_effect=[badging, subprocess.CalledProcessError(1, 'apksigner verify')]), self.assertRaises(subprocess.CalledProcessError):
            release.sign()
        self.assertFalse((dist / release.MANIFEST).exists())
        self.assertFalse(list(self.root.glob('paperflow-signing-*')))

    def test_build_has_no_fallback_for_non_release_events(self):
        state_file = self.root / 'state.json'; state_file.write_text(json.dumps(dict(self.state, run=91)))
        with patch.object(release, 'STATE', state_file), patch.dict(os.environ, GITHUB_EVENT_NAME='push'), patch.object(release.subprocess, 'run') as command, self.assertRaises(ValueError): release.build()
        command.assert_not_called()

    def test_different_signing_certificates_prevent_completion(self):
        state_file = self.root / 'state.json'; state_file.write_text(json.dumps(dict(self.state, run=91)))
        dist = self.root / 'dist'
        env = dict(ANDROID_HOME=str(self.root), RUNNER_TEMP=str(self.root), PAPERFLOW_KEYSTORE_BASE64=base64.b64encode(b'existing-key').decode(), PAPERFLOW_STORE_PASSWORD='fixture', PAPERFLOW_KEY_ALIAS='entry', PAPERFLOW_KEY_PASSWORD='fixture')
        badging = "package: name='com.example.myapplication' versionCode='1002002' versionName='1.2.2'"
        responses = [item for digest in ('c', 'd', 'c') for item in (badging, 'Signer #1 certificate SHA-256 digest: ' + digest * 64)]
        def process(args, **kwargs):
            if '--out' in args: Path(args[args.index('--out') + 1]).write_bytes(b'signed-fixture')
        with patch.object(release, 'STATE', state_file), patch.object(release, 'DIST', dist), patch.dict(os.environ, env), patch.object(release.subprocess, 'run', side_effect=process), patch.object(release, 'run', side_effect=responses), self.assertRaisesRegex(ValueError, 'same signing certificate'):
            release.sign()
        self.assertFalse((dist / release.MANIFEST).exists())

    def test_failed_build_stops_before_other_variants_or_signing(self):
        state_file = self.root / 'state.json'; state_file.write_text(json.dumps(dict(self.state, run=91)))
        with patch.object(release, 'STATE', state_file), patch.object(release, 'validate_source'), patch.object(release.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'gradle')) as command, self.assertRaises(subprocess.CalledProcessError): release.build()
        self.assertEqual(1, command.call_count)
        self.assertIn('-PpaperflowReleaseVersion=1.2.2', command.call_args.args[0])


class ReleaseWorkflowBoundaryTest(unittest.TestCase):
    def test_only_published_release_workflow_can_invoke_apk_delivery(self):
        import re
        root = Path(__file__).resolve().parents[2]
        for workflow in (root / '.github/workflows').glob('*.yml'):
            text = workflow.read_text('utf-8')
            delivers = re.search(r'\b(?:assemble|package|bundle)\w*Release\b|release_android\.py (?:build|sign|publish)\b', text)
            if delivers:
                self.assertEqual('android-release.yml', workflow.name)
                trigger = text.split('permissions:', 1)[0]
                self.assertRegex(trigger, r'release:\s+types: \[published\]')
                self.assertNotRegex(trigger, r'push:|pull_request:|workflow_dispatch:|workflow_run:')
                self.assertRegex(text, r'(?m)^permissions: \{\}$')
                # Only the final asset-publishing job may receive repository write access.
                before_publish, publisher = text.split('\n  publish:\n', 1)
                self.assertNotRegex(before_publish, r'contents: write|write-all')
                self.assertRegex(publisher, r'permissions:\s+contents: write\s+actions: read')
        ci = (root / '.github/workflows/android-ci.yml').read_text('utf-8')
        self.assertNotIn('outputs/apk', ci)
        self.assertIn(':app:minifyReleaseWithR8', ci)
        self.assertIn('check_android_manifest.py', ci)
        self.assertIn('check_apk.py --native-dir', ci)


class NativeDirectoryVerificationTest(unittest.TestCase):
    def test_merged_library_tree_preserves_apk_elf_checks(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
                (root / abi).mkdir()
                header = bytearray(20); header[:4] = b'\x7fELF'; header[4], machine = ARCHITECTURES[abi]; header[5] = 1; struct.pack_into('<H', header, 18, machine)
                for name in REQUIRED:(root / abi / name).write_bytes(header)
            self.assertEqual(3 * len(REQUIRED), check_directory(root, 'all'))
            (root / 'x86').mkdir(); (root / 'x86' / 'unselected-aar.so').write_bytes(b'unselected')
            self.assertEqual(3 * len(REQUIRED), check_directory(root, 'all'))
            (root / 'arm64-v8a' / next(iter(REQUIRED))).write_bytes(b'invalid')
            with self.assertRaises(ValueError): check_directory(root, 'all')
            with self.assertRaises(ValueError): check_directory(root / 'missing', 'all')


if __name__ == '__main__':
    unittest.main()
