"""Read-only S1-A checks; no root privileges, useradd, chpasswd or sshd needed."""
import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import os
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


if __name__ == '__main__':
    unittest.main()
