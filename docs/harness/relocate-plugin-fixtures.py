"""Relocate the two pinned binary test fixtures; never used for production plugins.

Use --plugin-api-only to migrate the current fixtures to org.flow.plugin.api.
Run with --source-root pointing to an extracted original source archive to reproduce
the original namespace migration (requires the historical upstream-manifest.json).
Source hashes must match that manifest. Licenses and Maven
provenance remain unchanged; class constant-pool references and plugin resources move.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = (
    'core/src/test/resources/plugins/plugin-template-test-0.24.0-SNAPSHOT.jar',
    'core/src/test/resources/plugins/plugin-redis-with-ui-1.2.3.jar',
)


def relocate_plugin_api(data):
    """Move only the plugin API references and service path in existing test JARs."""
    for separator in (b'.', b'/'):
        prefix = separator.join((b'org', b'flow', b'core', b'models'))
        target = separator.join((b'org', b'flow', b'plugin', b'api'))
        data = data.replace(prefix + separator + b'annotations', target + separator + b'annotations')
        data = data.replace(prefix + separator + b'enums' + separator + b'MonacoLanguages',
                            target + separator + b'enums' + separator + b'MonacoLanguages')
        data = re.sub(re.escape(prefix + separator + b'Plugin') + rb'(?![A-Za-z0-9_])',
                      lambda _: target + separator + b'Plugin', data)
    return data


def relocate(data):
    # These fixtures contain illustrative product URLs, not endpoints exercised by tests.
    data = re.sub(rb'https?://[^\s<>"\)\]]*kestra[^\s<>"\)\]]*', b'https://example.com', data)
    for old, new in (
        (b'io/kestra', b'org/flow'), (b'io.kestra', b'org.flow'),
        (b'io_kestra', b'org_flow'), (b'Kestra', b'Flow'),
        (b'KESTRA', b'FLOW'), (b'kestra', b'flow'),
    ):
        data = data.replace(old, new)
    return relocate_plugin_api(data)


def relocate_class(data, transform=relocate):
    assert data[:4] == b'\xca\xfe\xba\xbe', 'Invalid class file'
    count = struct.unpack_from('>H', data, 8)[0]
    output = bytearray(data[:10])
    offset, index = 10, 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4,
             11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[offset]
        output.append(tag)
        offset += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, offset)[0]
            offset += 2
            # Replace ASCII references directly: preserve other modified-UTF-8 bytes.
            value = transform(data[offset:offset + length])
            output.extend(struct.pack('>H', len(value)))
            output.extend(value)
            offset += length
        else:
            assert tag in sizes, f'Unsupported constant-pool tag: {tag}'
            length = sizes[tag]
            output.extend(data[offset:offset + length])
            offset += length
            if tag in (5, 6):
                index += 1
        index += 1
    output.extend(data[offset:])
    return bytes(output)


def relocate_manifest(data, transform=relocate):
    data = transform(data.replace(b'\r\n ', b'').replace(b'\n ', b''))
    lines = []
    for line in data.splitlines():
        while len(line) > 72:
            lines.append(line[:72])
            line = b' ' + line[72:]
        lines.append(line)
    return b'\r\n'.join(lines).rstrip(b'\r\n') + b'\r\n\r\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--plugin-api-only', action='store_true',
                        help='Update existing Flow test fixtures to org.flow.plugin.api without the original manifest')
    args = parser.parse_args()
    transform = relocate_plugin_api if args.plugin_api_only else relocate
    manifest = None if args.plugin_api_only else json.loads((ROOT / 'upstream-manifest.json').read_text())
    for name in FIXTURES:
        source, target = args.source_root / name, ROOT / name
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        if manifest is not None:
            assert digest == manifest['files'][name], f'Expected pinned original fixture: {source}'
        temporary = target.with_suffix('.relocated.jar')
        with zipfile.ZipFile(source) as archive, zipfile.ZipFile(temporary, 'w') as output:
            names = set()
            for info in archive.infolist():
                assert not re.match(r'META-INF/.*\.(SF|RSA|DSA|EC)$', info.filename), 'Signed fixture unsupported'
                data = archive.read(info)
                relocated = copy.copy(info)
                provenance = 'LICENSE' in info.filename.upper() or info.filename.startswith('META-INF/maven/')
                if not provenance:
                    relocated.filename = transform(info.filename.encode()).decode()
                    if info.filename.endswith('.class'):
                        data = relocate_class(data, transform)
                    elif info.filename == 'META-INF/MANIFEST.MF':
                        data = relocate_manifest(data, transform)
                    elif b'\0' not in data:
                        data = transform(data)
                assert relocated.filename not in names, f'Duplicate archive entry: {relocated.filename}'
                names.add(relocated.filename)
                output.writestr(relocated, data)
        temporary.replace(target)
        print(f'Relocated {name}')


if __name__ == '__main__':
    main()
