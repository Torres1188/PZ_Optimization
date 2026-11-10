#!/usr/bin/env python3
"""Prepare the complete existing donor resources around reviewed B42.22.2 classes.

This is a build-only operation. It neither installs files nor starts the game.
Shader generation reuses the original build.sh recipes against the selected game.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
GAME_SHA = '3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287'


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            digest.update(block)
    return digest.hexdigest()


def shell_path(path: Path) -> str:
    value = path.resolve().as_posix()
    if len(value) > 2 and value[1] == ':':
        return '/' + value[0].lower() + value[2:]
    return value


def quoted(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


def prepare(classes: Path, game: Path, output: Path, bash: str) -> None:
    classes, game = classes.resolve(strict=True), game.resolve(strict=True)
    output = output.resolve()
    if sha(game) != GAME_SHA:
        raise ValueError('Game jar is not the exact reviewed B42.22.2 artifact')
    if output.exists() or not output.is_relative_to(ROOT / 'build'):
        raise ValueError('Select a new output directory below this checkout build/')
    compiled_before = {p.relative_to(classes).as_posix(): sha(p) for p in classes.rglob('*.class')}
    if not compiled_before or not (classes / 'pzopt/Config.class').is_file():
        raise ValueError('Input must contain the complete reviewed donor class build')
    shutil.copytree(classes, output)
    subprocess.run([sys.executable, str(ROOT / 'scripts/option-classes.py')], cwd=ROOT, check=True)
    shutil.copytree(ROOT / 'src/lua', output / 'media/lua', dirs_exist_ok=True)
    shutil.copytree(ROOT / 'src/media', output / 'media', dirs_exist_ok=True)
    for license_path in (ROOT / 'src/vendor').glob('*/LICENSE'):
        target = output / 'pzopt/licenses' / license_path.parent.name
        target.mkdir(parents=True, exist_ok=True)
        shutil.copy2(license_path, target / 'LICENSE')
        readme = license_path.parent / 'README.md'
        if readme.is_file():
            shutil.copy2(readme, target / 'README.md')
    # Keep original resource names. Legacy installer entry points refuse managed installations;
    # no loose-file installer is invoked by this build pipeline or adapter.
    uninstall = output / 'pzopt/uninstall'
    uninstall.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / 'install.ps1', uninstall / 'install.ps1')
    shutil.copy2(ROOT / 'install.sh', uninstall / 'install.bash')
    for name in ('Uninstall-PZ-Optimization.cmd', 'uninstall-pz-optimization.bash'):
        shutil.copy2(ROOT / 'src/uninstall' / name, output / name)
    build_script = (ROOT / 'scripts/build.sh').read_text(encoding='utf-8')
    begin = build_script.index('# Puddle shader variants')
    end = build_script.index('# Extract the stock copies')
    recipe = output.parent / (output.name + '-resources.sh')
    header = '#!/usr/bin/env bash\nset -euo pipefail\n'
    header += 'REPO=' + quoted(shell_path(ROOT)) + '\n'
    header += 'SRC=' + quoted(shell_path(ROOT / 'src')) + '\n'
    header += 'OUT=' + quoted(shell_path(output)) + '\n'
    header += 'JAR=' + quoted(shell_path(game)) + '\n'
    header += 'python3() { ' + quoted(shell_path(Path(sys.executable))) + ' "$@"; }\n'
    recipe.write_text(header + build_script[begin:end], encoding='utf-8', newline='\n')
    resource_run = subprocess.run([bash, '--noprofile', '--norc', str(recipe)], cwd=ROOT, capture_output=True, text=True)
    (output.parent / (output.name + '-resources.log')).write_text(resource_run.stdout + resource_run.stderr, encoding='utf-8')
    if resource_run.returncode or 'not found next to the jar' in resource_run.stderr:
        raise RuntimeError('Shader generation incomplete: ' + resource_run.stdout + resource_run.stderr)
    subprocess.run([sys.executable, str(ROOT / 'scripts/override-methods.py'), str(output / 'pzopt')], cwd=ROOT, check=True)
    with zipfile.ZipFile(game) as jar:
        native = sorted(name[:-6] for name in compiled_before if name in jar.namelist())
        top = [name for name in native if '$' not in name]
        info = ['# generated for the exact reviewed B42.22.2 ZombieBuddy review build',
                'revision=3d39148846', 'jar.sha256=' + GAME_SHA, 'jar.size=' + str(game.stat().st_size),
                'release=25', 'commit=' + subprocess.check_output(['git', 'rev-parse', '--short', 'HEAD'], cwd=ROOT, text=True).strip() + '-review',
                'built=' + str(int(time.time())), 'overrides=' + ','.join(top)]
        for binary in native:
            info.append('stock.' + binary.replace('/', '.') + '=' + hashlib.sha256(jar.read(binary + '.class')).hexdigest())
    (output / 'pzopt/build-info.properties').write_text('\n'.join(info) + '\n', encoding='utf-8')
    compiled_after = {p.relative_to(classes).as_posix(): sha(p) for p in classes.rglob('*.class')}
    if compiled_after != compiled_before:
        raise RuntimeError('Input classes changed during preparation')
    receipt = {'passed': True, 'gameJarSha256': GAME_SHA, 'inputClasses': len(compiled_before),
               'preparedFiles': sum(1 for p in output.rglob('*') if p.is_file()),
               'prepared': str(output), 'sourceClassesChanged': False,
               'shaderRecipes': 'Unchanged upstream build.sh resource-generation section',
               'usaCodeIncluded': False, 'gameLaunched': False}
    (output.parent / (output.name + '-preparation.json')).write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--game-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--bash', default='C:/Program Files/Git/bin/bash.exe' if sys.platform == 'win32' else 'bash')
    args = parser.parse_args()
    prepare(args.classes, args.game_jar, args.output, args.bash)
