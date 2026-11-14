"""Run the Heroic guest scripts against a stub AppImage, session runner and ARM64 Proton.

Covers tools/linuxfs/overlay/usr/local/bin/droiddeck-heroic and droiddeck-heroic-wine:
the session entry must reach the AppImage runner the session uses, and the Wine build
Heroic selects must run through an aarch64 Proton on Heroic's own prefix.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zlib

REPO = Path(__file__).resolve().parents[2]
BIN = REPO / 'tools/linuxfs/overlay/usr/local/bin'
HEROIC = BIN / 'droiddeck-heroic'
WINE = BIN / 'droiddeck-heroic-wine'
AARCH64 = 0xB7
X86_64 = 0x3E

RUNNER_STUB = """#!/bin/sh
: > "$HOME/runner.argv"
for arg in "$@"; do printf '%s\\n' "$arg" >> "$HOME/runner.argv"; done
exit 0
"""

PROTON_STUB = """#!/bin/sh
printf '%s' "$0" > "$HOME/proton.path"
: > "$HOME/proton.argv"
for arg in "$@"; do printf '%s\\n' "$arg" >> "$HOME/proton.argv"; done
env > "$HOME/proton.env"
exit 0
"""

WINESERVER_STUB = """#!/bin/sh
printf '%s' "$*" > "$HOME/wineserver.args"
exit 0
"""

PROTON_EXTRA_STUB = """#!/bin/sh
printf '%s\\n' "$@" > "$HOME/proton-extra.argv"
exit 0
"""


def elf(machine):
    head = bytearray(20)
    head[0:4] = b'\x7fELF'
    head[4] = 2
    head[5] = 1
    head[6] = 1
    head[8] = 0x41
    head[9] = 0x49
    head[10] = 2
    head[18] = machine & 0xff
    head[19] = (machine >> 8) & 0xff
    return bytes(head) + b'\0' * 44


def write(path, text, mode=0o644):
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(text, bytes):
        path.write_bytes(text)
    else:
        path.write_text(text, encoding='utf-8')
    path.chmod(mode)
    return path


class HeroicLaunchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='droiddeck-heroic-')
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name) / 'home'
        self.home.mkdir()
        self.shim = self.home / 'bin'
        for name in (HEROIC, WINE):
            dest = write(self.shim / name.name, name.read_text(encoding='utf-8'), 0o755)
            self.assertEqual(dest.read_bytes(), name.read_bytes())
        write(self.shim / 'droiddeck-appimage-run', RUNNER_STUB, 0o755)
        write(self.shim / 'droiddeck-proton-extra', PROTON_EXTRA_STUB, 0o755)
        self.state = self.home / '.local/share/droiddeck-heroic'
        self.version = '2.22.4'
        self.image = self.state / f'app/{self.version}/Heroic-{self.version}-linux-arm64.AppImage'
        write(self.image, 'not a real AppImage; the unpacked tree is what runs\n')
        write(self.state / f'app/{self.version}/app/AppRun', '#!/bin/sh\nexit 0\n', 0o755)
        write(self.state / 'current.json', json.dumps({'app': self.version, 'file': self.image.name}))
        self.steam = self.home / '.local/share/Steam'
        self.heroic = self.home / '.config/heroic'

    def proton(self, name, machine, preferred=False):
        folder = self.steam / 'compatibilitytools.d' / name
        write(folder / 'proton', PROTON_STUB, 0o755)
        write(folder / 'files/bin/wine', elf(machine), 0o755)
        write(folder / 'files/bin/wineserver', WINESERVER_STUB, 0o755)
        return folder

    def env(self, **extra):
        base = {'HOME': str(self.home), 'PATH': '/usr/local/bin:/usr/bin:/bin', 'DISPLAY': ':0'}
        base.update(extra)
        return base

    def run_script(self, script, args=(), **extra):
        return subprocess.run(
            [str(self.shim / script), *args], env=self.env(**extra),
            capture_output=True, text=True, timeout=120,
        )

    def read_lines(self, name):
        path = self.home / name
        self.assertTrue(path.is_file(), f'{name} was not written; the script stopped early')
        return path.read_text(encoding='utf-8').splitlines()

    def test_session_entry_runs_the_unpacked_heroic_tree_through_the_runner(self):
        self.proton('GE-Proton10-1', AARCH64)
        broken = {'wineVersion': {'bin': str(self.heroic / 'tools/wine/gone/bin/wine'), 'name': 'Gone', 'type': 'wine'}}
        write(self.heroic / 'GamesConfig/Broken.json', json.dumps(broken))
        result = self.run_script('droiddeck-heroic', ['--hidden'])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        argv = self.read_lines('runner.argv')
        tree = str(self.state / f'app/{self.version}')
        self.assertEqual(argv[0], tree, argv)
        self.assertIn('--no-sandbox', argv)
        self.assertIn('--hidden', argv)
        self.assertTrue((self.state / f'app/{self.version}/arch').read_text().strip() == 'arm64')
        self.assertEqual((self.state / f'app/{self.version}/fex').read_text().strip(), 'off')
        self.assertFalse((self.home / 'proton-extra.argv').exists(), 'a Proton was already installed')

    def test_session_entry_selects_the_wine_build_and_repairs_broken_game_settings(self):
        self.proton('GE-Proton10-1', AARCH64)
        write(self.heroic / 'GamesConfig/Broken.json',
              json.dumps({'wineVersion': {'bin': str(self.heroic / 'tools/wine/gone/bin/wine'), 'name': 'Gone', 'type': 'wine'}}))
        result = self.run_script('droiddeck-heroic')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        config = json.loads((self.heroic / 'config.json').read_text(encoding='utf-8'))
        wine = config['defaultSettings']['wineVersion']
        tools = self.heroic / 'tools/wine/droiddeck-arm64/bin'
        self.assertEqual(wine['name'], 'Wine - DroidDeck ARM64')
        self.assertEqual(wine['type'], 'wine')
        self.assertEqual(Path(wine['bin']), tools / 'wine')
        self.assertFalse(config['defaultSettings']['useGameMode'])
        self.assertFalse(config['defaultSettings']['autoInstallDxvk'])
        for name in ('wine', 'wine64', 'wineserver'):
            shim = tools / name
            self.assertTrue(shim.is_file(), f'{name} was not registered for Heroic')
            self.assertTrue(os.access(shim, os.X_OK))
            self.assertIn(str(self.shim / 'droiddeck-heroic-wine'), shim.read_text(encoding='utf-8'))
        repaired = json.loads((self.heroic / 'GamesConfig/Broken.json').read_text(encoding='utf-8'))
        self.assertEqual(Path(repaired['wineVersion']['bin']), tools / 'wine')

    def test_missing_proton_is_fetched_in_the_background(self):
        result = self.run_script('droiddeck-heroic')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        verify = self.home / 'proton-extra.argv'
        for _ in range(100):
            if verify.exists():
                break
            subprocess.run(['sleep', '0.05'])
        self.assertEqual(verify.read_text(encoding='utf-8').splitlines(), [str(self.steam), 'droiddeck'])

    def test_wine_build_runs_the_game_through_the_aarch64_proton_on_the_heroic_prefix(self):
        self.proton('AAA-Proton-x86', X86_64)
        chosen = self.proton('GE-Proton10-1', AARCH64)
        prefix = self.home / 'Games/Heroic/Prefixes/Some Game'
        result = self.run_script('droiddeck-heroic-wine', ['C:\\games\\game.exe', '-windowed'], WINEPREFIX=str(prefix))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        argv = self.read_lines('proton.argv')
        self.assertEqual(Path(self.read_lines('proton.path')[0]), (chosen / 'proton').resolve(),
                         'the x86 Proton was chosen on an arm64 guest')
        self.assertEqual(argv, ['run', 'C:\\games\\game.exe', '-windowed'])
        env = dict(line.split('=', 1) for line in self.read_lines('proton.env') if '=' in line)
        compat = self.steam / 'steamapps/compatdata' / str(zlib.crc32(str(prefix).encode('utf-8')))
        self.assertEqual(env['STEAM_COMPAT_DATA_PATH'], str(compat))
        self.assertEqual(env['WINEPREFIX'], str(prefix))
        self.assertEqual(env['STEAM_COMPAT_INSTALL_PATH'], os.getcwd())
        self.assertEqual(os.path.realpath(compat / 'pfx'), os.path.realpath(prefix))

    def test_version_and_wineserver_come_from_the_chosen_proton(self):
        chosen = self.proton('GE-Proton10-1', AARCH64)
        result = self.run_script('droiddeck-heroic-wine', ['--version'])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('DroidDeck ARM64 (GE-Proton10-1)', result.stdout)
        self.assertFalse((self.home / 'proton.argv').exists())
        result = self.run_script('droiddeck-heroic-wine', ['wineserver', '-k'], WINEPREFIX=str(self.home / 'prefix'))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.home / 'wineserver.args').read_text(encoding='utf-8'), '-k')
        self.assertTrue((chosen / 'files/bin/wineserver').is_file())

    def test_wine_build_reports_a_missing_proton_instead_of_failing_obscurely(self):
        result = self.run_script('droiddeck-heroic-wine', ['C:\\games\\game.exe'])
        self.assertEqual(result.returncode, 69)
        self.assertIn('no ARM64 Proton', result.stderr)


if __name__ == '__main__':
    unittest.main()
