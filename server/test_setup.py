"""Read-only S1-A checks; no root privileges, useradd, chpasswd or sshd needed."""
import importlib.machinery
import contextlib
import io
import json
import importlib.util
from pathlib import Path
import tempfile
import os
import base64
import struct
import types
import unittest
from unittest import mock

path = Path(__file__).with_name('aap2-setup')
loader = importlib.machinery.SourceFileLoader('aap2_setup', str(path))
spec = importlib.util.spec_from_loader(loader.name, loader)
aap = importlib.util.module_from_spec(spec)
loader.exec_module(aap)


class CheckTests(unittest.TestCase):
    def test_policy_matches_expected_exactly(self):
        for role in (aap.GROUP_ENROLL, aap.GROUP_PROXY):
            checks = aap.Checks()
            aap.check_policy(checks, dict(aap.POLICY[role]), role)
            self.assertEqual(checks.failed, 0)

    def test_policy_fails_on_alternative_authorized_keys_source(self):
        actual = dict(aap.POLICY[aap.GROUP_PROXY])
        actual['authorizedkeysfile'] = '.ssh/authorized_keys'
        actual['allowtcpforwarding'] = 'yes'
        checks = aap.Checks()
        aap.check_policy(checks, actual, aap.GROUP_PROXY)
        self.assertEqual(checks.failed, 2)

    def test_doctor_non_root_is_read_only_and_fails(self):
        with mock.patch.object(aap.os, 'geteuid', return_value=1000), \
             mock.patch.object(aap.subprocess, 'run', side_effect=AssertionError('unexpected command')):
            self.assertEqual(aap.doctor(types.SimpleNamespace(enroll_user=None, proxy_user=None)), 1)

    def test_uid_floor_is_not_enough_by_itself(self):
        role = aap.GROUP_PROXY
        user = types.SimpleNamespace(pw_uid=1000, pw_gid=111, pw_shell='/usr/sbin/nologin')
        with mock.patch.object(aap.pwd, 'getpwnam', return_value=user), \
             mock.patch.object(aap.grp, 'getgrnam', return_value=types.SimpleNamespace(gr_gid=111)):
            checks = aap.Checks()
            self.assertIsNone(aap.managed_account(checks, 'testuser', role))
            self.assertGreater(checks.failed, 0)

    def test_uid_1001_but_wrong_group_rejected(self):
        role = aap.GROUP_PROXY
        user = types.SimpleNamespace(pw_uid=1001, pw_gid=111, pw_shell='/usr/sbin/nologin')
        with mock.patch.object(aap.pwd, 'getpwnam', return_value=user), \
             mock.patch.object(aap.grp, 'getgrnam', return_value=types.SimpleNamespace(gr_gid=200)), \
             mock.patch.object(aap.os, 'getgrouplist', return_value=[111]):
            checks = aap.Checks()
            self.assertIsNone(aap.managed_account(checks, 'testuser', role))
            self.assertGreater(checks.failed, 0)

    def test_roles_must_not_overlap(self):
        user = types.SimpleNamespace(pw_uid=1001, pw_gid=111, pw_shell='/usr/sbin/nologin')
        def groups(role):
            return types.SimpleNamespace(gr_gid=200 if role == aap.GROUP_PROXY else 201)
        with mock.patch.object(aap.pwd, 'getpwnam', return_value=user), \
             mock.patch.object(aap.grp, 'getgrnam', side_effect=groups), \
             mock.patch.object(aap.os, 'getgrouplist', return_value=[111, 200, 201]):
            checks = aap.Checks()
            self.assertIsNone(aap.managed_account(checks, 'testuser', aap.GROUP_PROXY))
            self.assertGreater(checks.failed, 0)

    def test_ssh_context_comma_injection_rejected_before_execution(self):
        with mock.patch.object(aap, 'command_output', side_effect=AssertionError('must not exec')):
            checks = aap.Checks()
            self.assertIsNone(aap.read_effective_config(checks, '/usr/sbin/sshd',
                'aap2dev', '127.0.0.1', 'localhost,addr=127.0.0.1', '127.0.0.1', 22))
            self.assertEqual(checks.failed, 1)

    def test_ssh_context_valid_parsing(self):
        output = 'authenticationmethods publickey\nauthorizedkeysfile /etc/ssh/aap2-authorized-keys/%u\n'
        with mock.patch.object(aap, 'command_output', return_value=output):
            checks = aap.Checks()
            actual = aap.read_effective_config(checks, '/usr/sbin/sshd',
                'aap2dev', '192.0.2.13', 'example.net', '127.0.0.1', 22)
            self.assertEqual(actual['authenticationmethods'], 'publickey')
            self.assertEqual(checks.failed, 0)

    def test_directory_symlink_refused(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d, 'real')
            folder.mkdir()
            link = Path(d, 'alias')
            link.symlink_to(folder, target_is_directory=True)
            checks = aap.Checks()
            self.assertFalse(aap.check_dir(checks, link, 0o755))
            self.assertEqual(checks.failed, 1)

    def test_hardlinked_upload_refused(self):
        with tempfile.TemporaryDirectory() as d:
            original = Path(d, 'original')
            original.write_text('x')
            linked = Path(d, 'linked')
            os.link(str(original), str(linked))
            checks = aap.Checks()
            st = linked.stat()
            self.assertFalse(aap.check_file(checks, linked, st.st_uid, st.st_gid, 0o600))
            self.assertEqual(checks.failed, 1)

    def test_username_injection_rejected(self):
        checks = aap.Checks()
        with mock.patch.object(aap.pwd, 'getpwnam', side_effect=AssertionError('must not reach NSS')):
            self.assertIsNone(aap.managed_account(checks, '--root', aap.GROUP_PROXY))
            self.assertEqual(checks.failed, 1)


class LifecycleTests(unittest.TestCase):
    def test_enroll_cli_accepts_missing_client_addr_without_guessing_phone_ip(self):
        argv = ['aap2-setup', 'enroll', 'Cruise2', '--manual-code',
                '--server', 'ssh.example.net:22225', '--server-addr', '192.0.2.10']
        seen = []
        def fake_enroll(args):
            seen.append(args)
            return 0
        with mock.patch.object(aap.sys, 'argv', argv), \
             mock.patch.object(aap, 'enroll', side_effect=fake_enroll):
            self.assertEqual(aap.main(), 0)
        self.assertEqual(len(seen), 1)
        self.assertEqual(seen[0].client_addr, '198.51.100.1')
        self.assertEqual(seen[0].server_addr, '192.0.2.10')
        self.assertTrue(seen[0].manual_code)

    def test_pin_always_ten_digits_including_leading_zeros(self):
        with mock.patch.object(aap.secrets, 'randbelow', return_value=12):
            self.assertEqual(aap.generate_pin(), '0000000012')
        with mock.patch.object(aap.secrets, 'randbelow', return_value=9999999999):
            self.assertEqual(aap.generate_pin(), '9999999999')

    def test_doctor_preflight_refuses_missing_cleanup_timer(self):
        with mock.patch.object(aap, 'timer_active', return_value=False), \
             mock.patch.object(aap, 'require_root'), \
             mock.patch.object(aap, 'verified_dir'), \
             mock.patch.object(aap.grp, 'getgrnam',
                               return_value=types.SimpleNamespace(gr_gid=111)):
            with self.assertRaisesRegex(aap.SetupError, 'cleanup.timer'):
                aap.require_preflight()

    def test_mismatched_home_refuses_account_mutation(self):
        role = aap.GROUP_ENROLL
        account = {'name': 'aap2e123456abcdef', 'uid': 1001, 'gid': 222,
                   'home': '/', 'verified': True}
        record = {'users': {role: account}}
        user = types.SimpleNamespace(pw_uid=1001, pw_gid=222,
                                     pw_dir='/home/someone', pw_shell='/usr/sbin/nologin',
                                     pw_name=account['name'])
        with mock.patch.object(aap.pwd, 'getpwnam', return_value=user), \
             mock.patch.object(aap.grp, 'getgrnam',
                               return_value=types.SimpleNamespace(gr_gid=222)):
            with self.assertRaisesRegex(aap.SetupError, 'NSS identity changed'):
                aap.check_real_identity(record, role)

    def test_unverified_uid_never_allows_modification(self):
        role = aap.GROUP_ENROLL
        record = {'users': {role: {'name': 'aap2e123456abcdef',
                  'uid': 1001, 'gid': 222, 'home': '/', 'verified': False}}}
        with mock.patch.object(aap.pwd, 'getpwnam',
                               side_effect=AssertionError('NSS MUST NOT be consulted')):
            with self.assertRaisesRegex(aap.SetupError, 'MANUAL repair'):
                aap.check_real_identity(record, role)

    def make_enroll_fixture(self, directory, *, crash=None, manual_code=False):
        """Mock only external root-system mutations; use real JSON writes."""
        state = Path(directory, 'state')
        state.mkdir()
        created = {}
        events = []
        enrollment_id = '000012' if manual_code else '123456abcdef'

        def getpwnam(name):
            if name not in created:
                raise KeyError(name)
            return created[name]

        def getgroup(role):
            return types.SimpleNamespace(gr_gid=222 if role == aap.GROUP_ENROLL else 223)

        def mutation(argv, input_text=None):
            if argv[0].endswith('/useradd'):
                with (state / enrollment_id / 'metadata.json').open() as source:
                    current = json.load(source)
                events.append(('useradd', argv[-1], current['state'],
                               current['users'][aap.GROUP_ENROLL]['verified'],
                               current['users'][aap.GROUP_PROXY]['verified']))
                if crash == argv[-1]:
                    raise aap.SetupError('simulated crash after reservation, before useradd')
                role = argv[argv.index('-g') + 1]
                uid = int(argv[argv.index('-u') + 1])
                gid = getgroup(role).gr_gid
                created[argv[-1]] = types.SimpleNamespace(
                    pw_uid=uid, pw_gid=gid, pw_dir=argv[argv.index('-d') + 1],
                    pw_shell='/usr/sbin/nologin', pw_name=argv[-1])
            elif argv[0].endswith('/chpasswd'):
                with (state / enrollment_id / 'metadata.json').open() as source:
                    current = json.load(source)
                events.append(('chpasswd', current['state'],
                               current['users'][aap.GROUP_ENROLL]['verified'],
                               current['users'][aap.GROUP_PROXY]['verified'],
                               input_text.endswith(':0000000012\n')))
                if crash == 'chpasswd':
                    raise aap.SetupError('simulated crash during activation')
            else:
                self.fail('unexpected system mutation: {}'.format(argv))

        def directory_create(path, mode):
            path.mkdir(mode=mode)

        args = types.SimpleNamespace(device_name='TC21', server='example.net:22222',
             client_addr='192.0.2.17', server_addr='192.0.2.20',
             server_port='22', client_host='test-client.invalid',
             manual_code=manual_code)
        stack = contextlib.ExitStack()
        stack.enter_context(mock.patch.object(aap, 'STATE', state))
        stack.enter_context(mock.patch.object(aap, 'require_preflight', return_value='/usr/sbin/sshd'))
        stack.enter_context(mock.patch.object(aap, 'require_root'))
        stack.enter_context(mock.patch.object(aap, 'state_lock', side_effect=contextlib.nullcontext))
        stack.enter_context(mock.patch.object(aap, 'all_records', return_value=iter(())))
        stack.enter_context(mock.patch.object(aap.pwd, 'getpwnam', side_effect=getpwnam))
        stack.enter_context(mock.patch.object(aap.grp, 'getgrnam', side_effect=getgroup))
        stack.enter_context(mock.patch.object(aap.os, 'getgrouplist',
                                              side_effect=lambda name, gid: [gid]))
        stack.enter_context(mock.patch.object(aap.secrets, 'token_hex', return_value=enrollment_id))
        stack.enter_context(mock.patch.object(aap.secrets, 'randbelow', return_value=12))
        stack.enter_context(mock.patch.object(aap, 'unused_uid', side_effect=[1001, 1002]))
        stack.enter_context(mock.patch.object(aap, 'unused_port', return_value=11010))
        stack.enter_context(mock.patch.object(aap, 'new_root_dir', side_effect=directory_create))
        stack.enter_context(mock.patch.object(aap, 'make_chroot'))
        stack.enter_context(mock.patch.object(aap, 'new_file'))
        stack.enter_context(mock.patch.object(aap, 'verify_enrollment_policy'))
        stack.enter_context(mock.patch.object(aap, 'check_real_identity'))
        stack.enter_context(mock.patch.object(aap, 'timer_active', return_value=True))
        stack.enter_context(mock.patch.object(aap, 'call_mutation', side_effect=mutation))
        stack.enter_context(mock.patch.object(Path, 'glob', return_value=[]))
        return stack, state / enrollment_id / 'metadata.json', args, events

    def test_enrollment_reservation_precedes_account_creation_pin_last(self):
        with tempfile.TemporaryDirectory() as d:
            stack, meta_path, args, events = self.make_enroll_fixture(d)
            with stack, contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(aap.enroll(args), 0)
            self.assertEqual(events[0][0], 'useradd')
            self.assertEqual(events[0][2], 'creating')
            self.assertEqual(events[0][3:], (False, False))
            self.assertEqual(events[1][0], 'useradd')
            self.assertEqual(events[1][3:], (True, False))
            self.assertEqual(events[2], ('chpasswd', 'active', True, True, True))
            state = json.loads(meta_path.read_text())
            self.assertEqual(state['state'], 'active')
            self.assertNotIn('0000000012', meta_path.read_text())
            self.assertIn('One-time PIN (type all 10 digits): 0000000012', out.getvalue())

    def test_manual_setup_code_is_six_decimal_digits_and_keeps_ten_digit_pin(self):
        with tempfile.TemporaryDirectory() as d:
            stack, meta_path, args, events = self.make_enroll_fixture(
                d, manual_code=True)
            with stack, contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(aap.enroll(args), 0)
            state = json.loads(meta_path.read_text())
            self.assertEqual(state['id'], '000012')
            self.assertGreaterEqual(state['expires_at'] - state['created_at'], 1200)
            self.assertIn('20 minutes', out.getvalue())
            self.assertEqual(state['users'][aap.GROUP_ENROLL]['name'], 'aap2e000012')
            self.assertEqual(state['users'][aap.GROUP_PROXY]['name'], 'aap2p000012')
            self.assertIn('Phone setup code (6 digits, no prefix): 000012', out.getvalue())
            self.assertIn('One-time PIN (type all 10 digits): 0000000012', out.getvalue())
            self.assertNotIn('0000000012', meta_path.read_text())
            self.assertEqual(events[2][0], 'chpasswd')
            self.assertTrue(aap.ID_RE.fullmatch('000012'))
            self.assertTrue(aap.ID_RE.fullmatch('123456abcdef'))
            self.assertFalse(aap.ID_RE.fullmatch('12345'))
            self.assertFalse(aap.ID_RE.fullmatch('ab12cd'))

    def test_crash_before_useradd_never_activates_pin(self):
        with tempfile.TemporaryDirectory() as d:
            stack, meta_path, args, events = self.make_enroll_fixture(
                d, crash='aap2e123456abcdef')
            with stack, self.assertRaises(aap.SetupError):
                aap.enroll(args)
            state = json.loads(meta_path.read_text())
            self.assertEqual(state['state'], 'creating')
            self.assertFalse(state['users'][aap.GROUP_ENROLL]['verified'])
            self.assertEqual(len(events), 1)
            self.assertNotIn('chpasswd', str(events))

    def test_crash_during_chpasswd_keeps_expiring_active_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            stack, meta_path, args, events = self.make_enroll_fixture(d, crash='chpasswd')
            with stack, self.assertRaises(aap.SetupError):
                aap.enroll(args)
            state = json.loads(meta_path.read_text())
            self.assertEqual(state['state'], 'active')
            self.assertGreater(state['expires_at'], state['created_at'])
            self.assertNotIn('0000000012', meta_path.read_text())

    def test_cleanup_skips_broken_record_but_processes_other_expired_records(self):
        with tempfile.TemporaryDirectory() as d:
            state = Path(d, 'state')
            state.mkdir()
            bad = state / '111111111111'
            good = state / '222222222222'
            bad.mkdir()
            good.mkdir()
            user = 'aap2e222222222222'
            record = {'id': good.name, 'state': 'active', 'expires_at': 1,
                      'users': {aap.GROUP_ENROLL: {'name': user, 'uid': 1001,
                      'gid': 111, 'home': '/', 'verified': True}}}
            calls = []

            def read(enrollment_id):
                if enrollment_id == bad.name:
                    raise aap.SetupError('corrupt metadata')
                return record

            def call(argv, input_text=None):
                calls.append(tuple(argv))

            with mock.patch.object(aap, 'STATE', state), \
                 mock.patch.object(aap, 'require_root'), \
                 mock.patch.object(aap, 'verified_dir'), \
                 mock.patch.object(aap, 'state_lock', side_effect=contextlib.nullcontext), \
                 mock.patch.object(aap, 'read_metadata', side_effect=read), \
                 mock.patch.object(aap, 'check_real_identity'), \
                 mock.patch.object(aap, 'call_mutation', side_effect=call), \
                 mock.patch.object(aap.subprocess, 'run',
                                   return_value=types.SimpleNamespace(returncode=1)), \
                 mock.patch.object(aap, 'remove_chroot'), \
                 mock.patch.object(aap, 'atomic_json'), \
                 contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(aap.cleanup(None), 1)
            self.assertEqual(calls[0], ('/usr/sbin/usermod', '-L', user))
            self.assertEqual(calls[1], ('/usr/sbin/userdel', user))
            self.assertFalse(any('aap2p' in str(cmd) for cmd in calls))
            self.assertEqual(record['state'], 'expired')



class ApprovalTests(unittest.TestCase):
    @staticmethod
    def example_key(comment='aap2'):
        def field(data):
            return struct.pack('>I', len(data)) + data
        blob = field(b'ecdsa-sha2-nistp256') + field(b'nistp256') + \
            field(b'\x04' + b'\x00' * 64)
        value = 'ecdsa-sha2-nistp256 ' + base64.b64encode(blob).decode('ascii')
        return (value + (' ' + comment if comment else '') + '\n').encode('ascii')

    def test_strict_p256_canonical_and_authorized_options(self):
        key = self.example_key()
        canonical = aap.canonical_public_key(key)
        self.assertEqual(canonical.encode('ascii'), self.example_key(''))
        record = {'remote_port': 11010}
        authorized = aap.authorized_line(record, canonical)
        self.assertEqual(authorized.count('\n'), 1)
        self.assertTrue(authorized.startswith(
            'restrict,port-forwarding,permitlisten="127.0.0.1:11010" '))

    def test_reject_ssh_authorized_keys_option_injection(self):
        key = self.example_key()
        for bad in (b'command="true" ' + key, b'no-pty ' + key,
                    key + key, key.replace(b' ', b'  ', 1),
                    key.replace(b'\n', b'\r\n'),
                    b'ssh-rsa AAAA\n', b'',
                    key + b'\x00', key[:-1],
                    key.replace(b'aap2', b'aap2;rm'),
                    b' ' * 1025):
            with self.subTest(bad=bad[:50]):
                with self.assertRaises(aap.SetupError):
                    aap.canonical_public_key(bad)

    def test_reject_wrong_curve_blob(self):
        def field(data):
            return struct.pack('>I', len(data)) + data
        blob = field(b'ecdsa-sha2-nistp256') + field(b'other') + \
            field(b'\x04' + b'\x00' * 64)
        line = b'ecdsa-sha2-nistp256 ' + base64.b64encode(blob) + b'\n'
        with self.assertRaisesRegex(aap.SetupError, 'structure'):
            aap.canonical_public_key(line)

    def test_metadata_blocks_unknown_state(self):
        with tempfile.TemporaryDirectory() as d:
            ident = '123456abcdef'
            root = Path(d) / ident
            root.mkdir()
            (root / 'metadata.json').write_text(json.dumps({
                'id': ident, 'state': 'unknown', 'expires_at': 9999,
                'users': {}}))
            with mock.patch.object(aap, 'STATE', Path(d)), \
                 mock.patch.object(aap, 'verified_dir'), \
                 mock.patch.object(aap, 'verified_file'):
                with self.assertRaises(aap.SetupError):
                    aap.read_metadata(ident)

    def test_expired_approved_status_kept_on_temp_cleanup(self):
        record = {
            'id': '123456abcdef', 'state': 'approved', 'temp_removed': False,
            'users': {aap.GROUP_ENROLL: {'name': 'aap2e123456abcdef'}}
        }
        with mock.patch.object(aap, 'check_real_identity'), \
             mock.patch.object(aap, 'call_mutation'), \
             mock.patch.object(aap, 'remove_account'), \
             mock.patch.object(aap, 'remove_chroot'), \
             mock.patch.object(aap, 'atomic_json'):
            aap.remove_temporary(record)
        self.assertTrue(record['temp_removed'])
        self.assertEqual(record['state'], 'approved')

    def test_revoke_requires_confirmation_before_key_change(self):
        record = {'id': '123456abcdef', 'state': 'approved', 'device_name': 'TC21',
                  'users': {aap.GROUP_PROXY: {'name': 'aap2p123456abcdef'}},
                  'remote_port': 11010}
        with mock.patch.object(aap, 'require_root'), \
             mock.patch.object(aap, 'state_lock', side_effect=contextlib.nullcontext), \
             mock.patch.object(aap, 'read_metadata', return_value=record), \
             mock.patch('builtins.input', return_value='N'), \
             mock.patch.object(aap, 'atomic_json',
                               side_effect=AssertionError('must not mutate')):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(aap.revoke(types.SimpleNamespace(
                    enrollment_id=record['id'])), 1)



if __name__ == '__main__':
    unittest.main()
