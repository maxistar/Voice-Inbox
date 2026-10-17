import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve('notes_recognition/iosApp');
const targets = [
  {
    name: 'VoiceInbox',
    catalog: path.join(root, 'VoiceInbox/Localizable.xcstrings'),
    sources: path.join(root, 'VoiceInbox'),
    callPattern: /L10n\.(?:text|format|plural)\("([^"]+)"/g,
  },
  {
    name: 'VoiceInboxShareExtension',
    catalog: path.join(root, 'VoiceInboxShareExtension/Localizable.xcstrings'),
    sources: path.join(root, 'VoiceInboxShareExtension'),
    callPattern: /ShareExtensionL10n\.(?:text|format|plural)\("([^"]+)"/g,
  },
];
const locales = ['en', 'ru', 'de'];
const projectFile = fs.readFileSync(path.join(root, 'VoiceInbox.xcodeproj/project.pbxproj'), 'utf8');
const knownRegions = projectFile.match(/knownRegions\s*=\s*\(([^)]*)\)/)?.[1] ?? '';
const placeholders = value => [...value.matchAll(/%(?:\d+\$)?(?:@|d|s)/g)].map(match => match[0].replace(/^%\d+\$/, '%')).sort();
const collectSwiftFiles = directory => fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
  const file = path.join(directory, entry.name);
  return entry.isDirectory() ? collectSwiftFiles(file) : file.endsWith('.swift') ? [file] : [];
});

let failed = false;
for (const locale of locales) {
  if (!new RegExp(`\\b${locale}\\b`).test(knownRegions)) {
    failed = true;
    process.stderr.write(`Xcode project is missing supported region: ${locale}\n`);
  }
}
for (const target of targets) {
  const catalog = JSON.parse(fs.readFileSync(target.catalog, 'utf8'));
  const strings = catalog.strings ?? {};
  const errors = [];
  for (const [key, entry] of Object.entries(strings)) {
    const english = entry.localizations?.en?.stringUnit?.value;
    for (const locale of locales) {
      const value = entry.localizations?.[locale]?.stringUnit?.value;
      if (typeof value !== 'string' || value.length === 0) {
        errors.push(`${key}: missing ${locale} translation`);
      } else if (english && placeholders(english).join('|') !== placeholders(value).join('|')) {
        errors.push(`${key}: ${locale} placeholders differ from English`);
      }
    }
  }

  const source = collectSwiftFiles(target.sources).map(file => fs.readFileSync(file, 'utf8')).join('\n');
  for (const match of source.matchAll(target.callPattern)) {
    const key = match[1];
    const isPlural = /\.(?:plural)\(/.test(match[0]);
    if (!isPlural && !strings[key]) errors.push(`source localization key not in catalog: ${key}`);
    if (isPlural) {
      for (const form of ['one', 'other']) {
        if (!strings[`${key}.${form}`]) errors.push(`plural key missing form: ${key}.${form}`);
      }
      for (const form of ['few', 'many']) {
        if (!strings[`${key}.${form}`]) errors.push(`Russian plural key missing form: ${key}.${form}`);
      }
    }
  }

  if (errors.length) {
    failed = true;
    process.stderr.write(`${target.name}:\n${errors.map(error => `- ${error}`).join('\n')}\n`);
  } else {
    process.stdout.write(`${target.name}: ${Object.keys(strings).length} keys complete in ${locales.join(', ')}\n`);
  }
}

if (failed) process.exitCode = 1;
