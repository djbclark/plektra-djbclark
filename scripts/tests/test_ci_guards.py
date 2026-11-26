import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


catalog = module('ci_tests', SCRIPTS / 'ci-tests.py')
lint = module('check_lint', SCRIPTS / 'check-lint.py')


class CatalogGuardTest(unittest.TestCase):
    def test_duplicate_stale_and_reserved_assignments_fail(self):
        found = {'unit': {'example.SampleTest': 'test'}, 'instrumented': {}}
        for topics in ({'editing': []},
                       {'unsorted': ['example.SampleTest']},
                       {'editing': ['example.SampleTest'], 'ui': ['example.SampleTest']},
                       {'editing': ['example.SampleTest', 'example.RemovedTest']}):
            with self.subTest(topics=topics), self.assertRaises(ValueError):
                catalog.validate({'unit': topics, 'instrumented': {}}, found)

    def test_new_classes_run_once_in_unsorted_until_maintainer_assigns_them(self):
        found = {'unit': {'example.SampleTest': 'test', 'example.NewTest': 'testDebug'},
                 'instrumented': {'example.DeviceTest': 'androidTest'}}
        curated = {'unit': {'editing': ['example.SampleTest']}, 'instrumented': {}}
        catalog.validate(curated, found)
        self.assertEqual(['example.NewTest'], catalog.resolved_topics(curated, found)['unsorted'])
        self.assertEqual(['example.DeviceTest'],
                         catalog.resolved_topics(curated, found, 'instrumented')['unsorted'])
        curated['unit']['editing'].append('example.NewTest')
        catalog.validate(curated, found)
        self.assertEqual([], catalog.resolved_topics(curated, found)['unsorted'])

    def test_unsorted_cli_selects_only_new_tests_and_propagates_failures(self):
        found = {'unit': {'example.SampleTest': 'test', 'example.NewTest': 'testDebug'},
                 'instrumented': {}}
        curated = {'unit': {'editing': ['example.SampleTest']}, 'instrumented': {}}
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(catalog, 'ROOT', Path(tmp)), \
                patch.object(catalog, 'discover', return_value=found), \
                patch.object(catalog.json, 'loads', return_value=curated), \
                patch.object(catalog.subprocess, 'run') as run, \
                patch('sys.argv', ['ci-tests.py', 'run', '--topic', 'unsorted']):
            def execute(command, **kwargs):
                self.assertEqual(['example.NewTest'],
                                 [command[i + 1] for i, arg in enumerate(command) if arg == '--tests'])
                directory = Path(tmp) / 'app/build/test-results/testStableDebugUnitTest'
                directory.mkdir(parents=True)
                (directory / 'TEST-new.xml').write_text(
                    '<testsuite name="example.NewTest" tests="1" failures="1"/>')
                return type('Result', (), {'returncode': 1})()
            run.side_effect = execute
            self.assertEqual(1, catalog.main())
            run.assert_called_once()
            # No Gradle invocation when the remainder is empty, including release filtering.
            curated['unit']['editing'].append('example.NewTest')
            run.reset_mock()
            self.assertEqual(0, catalog.main())
            run.assert_not_called()
            curated['unit']['editing'].pop()
            with patch('sys.argv', ['ci-tests.py', 'run', '--topic', 'unsorted',
                                    '--variant', 'StableRelease']):
                self.assertEqual(0, catalog.main())
                run.assert_not_called()

    def test_inherited_test_without_annotations_is_discovered(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'app/src/test/java/InheritedTest.kt'
            path.parent.mkdir(parents=True)
            path.write_text('package example\nclass InheritedTest : BaseChecks()\n')
            self.assertEqual({'example.InheritedTest': 'test'}, catalog.discover(Path(tmp))['unit'])

    def test_empty_partial_unexpected_and_failed_reports_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            for xml in (None,
                        '<testsuite name="example.OtherTest" tests="1"/>',
                        '<testsuite name="example.SampleTest" tests="0"/>',
                        '<testsuite name="example.SampleTest" tests="1" skipped="1"/>',
                        '<testsuite name="example.SampleTest" tests="1" failures="1"/>'):
                report = directory / 'TEST-example.SampleTest.xml'
                if xml:
                    report.write_text(xml)
                with self.subTest(xml=xml), self.assertRaises(ValueError):
                    catalog.verify_results(directory, ['example.SampleTest'])
            report.write_text('<testsuite name="example.SampleTest" tests="2" skipped="1"/>')
            self.assertIn('2 tests, 1 skipped', catalog.verify_results(directory, ['example.SampleTest']))


class LintGuardTest(unittest.TestCase):
    def test_lint_paths_are_portable_and_source_changes_are_new_findings(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / 'lint.xml'
            def finding(path, source):
                report.write_text(f'<issues><issue id="Rule" severity="Error" message="Problem" errorLine1="{source}"><location file="{path}"/></issue></issues>')
                return lint.findings(report)
            before = finding('/Users/dev/project/app/src/main/Example.kt', 'oldCall()')
            self.assertEqual(before, finding('/home/runner/project/app/src/main/Example.kt', 'oldCall()'))
            self.assertEqual(1, sum((finding('/home/runner/project/app/src/main/Example.kt', 'newCall()') - before).values()))

    def test_line_moves_keep_identity_but_additional_errors_are_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / 'lint.xml'
            def findings(line, extra=''):
                report.write_text('<issues><issue id="MissingPermission" severity="Error" message="Permission required">'
                                  f'<location file="src/main/Example.kt" line="{line}"/></issue>{extra}</issues>')
                return lint.findings(report)
            before = findings(10)
            self.assertEqual(before, findings(20))
            duplicate = '<issue id="MissingPermission" severity="Error" message="Permission required"><location file="src/main/Example.kt" line="30"/></issue>'
            self.assertEqual(1, sum((findings(20, duplicate) - before).values()))
            changed = '<issue id="NewIssue" severity="Fatal" message="New failure"><location file="src/main/New.kt"/></issue>'
            self.assertEqual(1, sum((findings(20, changed) - before).values()))


paths = module('ci_paths', SCRIPTS / 'ci-paths.py')
variants = module('ci_variants', SCRIPTS / 'ci-variants.py')


class PathSelectionTest(unittest.TestCase):
    def test_only_clear_documentation_skips_android(self):
        self.assertEqual((False, False), paths.selection(['README.md', 'docs/setup.md']))
        for changed in ([], ['new/unknown.file'], ['app/src/main/README.md'], ['CMakeLists.txt'],
                        ['README.md', 'app/src/main/Example.kt']):
            with self.subTest(changed=changed):
                self.assertTrue(paths.selection(changed)[0])

    def test_release_sources_rules_dependencies_and_toolchain_build_minified_release(self):
        for changed in ('app/src/release/DebugShowcaseExport.kt', 'app/proguard-rules.pro',
                        'gradle/libs.versions.toml', 'app/build.gradle.kts', 'gradle.properties',
                        'gradle/wrapper/gradle-wrapper.properties', '.github/workflows/ci.yml'):
            with self.subTest(changed=changed):
                self.assertEqual((True, True), paths.selection([changed]))
        self.assertEqual((True, False), paths.selection(['app/src/main/Example.kt']))


class DuplicateReportTest(unittest.TestCase):
    def test_duplicate_suite_names_fail_even_with_different_file_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            for name in ('first', 'second'):
                (directory / f'TEST-{name}.xml').write_text('<testsuite name="example.SampleTest" tests="1"/>')
            with self.assertRaisesRegex(ValueError, 'duplicate='):
                catalog.verify_results(directory, ['example.SampleTest'])


class WorkflowTopicTest(unittest.TestCase):
    def test_missing_extra_and_duplicate_steps_fail(self):
        topics = {'unit': {'editing': ['example.SampleTest']}}
        editing = '        run: python3 scripts/ci-tests.py run --topic editing\n'
        unsorted = '        run: python3 scripts/ci-tests.py run --topic unsorted\n'
        valid = editing + unsorted
        catalog.validate_workflow(topics, valid)
        for workflow in ('', editing, unsorted + editing, valid + valid,
                         valid + valid.replace('editing', 'speech')):
            with self.subTest(workflow=workflow), self.assertRaises(ValueError):
                catalog.validate_workflow(topics, workflow)


class VariantContractTest(unittest.TestCase):
    EXPECTED_VARIANTS = {
        ('stable', 'release'): ('rocks.pkb.plektra', 'Plektra'),
        ('stable', 'debug'): ('rocks.pkb.plektra.djb', 'Plektra djb'),
        ('nightly', 'release'): ('rocks.pkb.plektra.nightly', 'Plektra Nightly'),
        ('nightly', 'debug'): ('rocks.pkb.plektra.nightly.djb', 'Plektra djb'),
        ('playStable', 'release'): ('rocks.pkb.plektra.unsovereign', 'Plektra'),
        ('playStable', 'debug'): ('rocks.pkb.plektra.unsovereign.djb', 'Plektra djb'),
        ('playNightly', 'release'): ('rocks.pkb.plektra.unsovereign.nightly', 'Plektra Nightly'),
        ('playNightly', 'debug'): ('rocks.pkb.plektra.unsovereign.nightly.djb', 'Plektra djb'),
        ('stableNoAccessibility', 'release'): ('rocks.pkb.plektra', 'Plektra'),
        ('stableNoAccessibility', 'debug'): ('rocks.pkb.plektra.djb', 'Plektra djb'),
        ('nightlyNoAccessibility', 'release'): ('rocks.pkb.plektra.nightly', 'Plektra Nightly'),
        ('nightlyNoAccessibility', 'debug'): ('rocks.pkb.plektra.nightly.djb', 'Plektra djb'),
    }

    def fixture(self, flavor='stable', build_type='release', *, fdroid=True):
        import xml.etree.ElementTree as ET
        package, label = self.EXPECTED_VARIANTS[(flavor, build_type)]
        nightly = 'nightly' in flavor.lower()
        play = flavor.startswith('play')
        accessibility = 'NoAccessibility' not in flavor
        icon = '@mipmap/ic_launcher_nightly' if nightly else '@mipmap/ic_launcher'
        version_name = '26.11-nightly' if nightly else '26.11'
        config = {
            'APPLICATION_ID': package, 'BUILD_TYPE': build_type, 'FLAVOR': flavor,
            'RELEASE_CHANNEL': 'nightly' if nightly else 'stable',
            'DISTRIBUTION_CHANNEL': 'play' if play else 'direct',
            'IS_FDROID_BUILD': fdroid and not play,
            'ENABLE_GITHUB_UPDATE_CHECKS': not (fdroid or play),
            'ENABLE_ACCESSIBILITY_SERVICE': accessibility,
            'APK_VARIANT': 'full' if accessibility else 'no-accessibility',
            'GITHUB_REPOSITORY': 'pkb-rocks/plektra', 'VERSION_NAME': version_name, 'VERSION_CODE': 26110000,
        }
        accessibility_service = (
            '<service android:name="rocks.pkb.plektra.inputmethod.ClicksLauncherButtonAccessibilityService"/>'
            if accessibility else ''
        )
        manifest = ET.fromstring(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="{package}" android:versionName="{version_name}" android:versionCode="26110000">
            <application android:label="{label}" android:icon="{icon}" android:roundIcon="{icon}_round">
            <service android:name="rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService"
                android:permission="android.permission.BIND_INPUT_METHOD" android:label="{label}"/>
            {accessibility_service}
            <provider android:name="rikka.shizuku.ShizukuProvider" android:authorities="{package}.shizuku"/>
            <provider android:name="androidx.core.content.FileProvider" android:authorities="{package}.fileprovider"/>
            </application></manifest>''')
        enabled = 'BlueLauncherAlias' if nightly else 'OrangeLauncherAlias'
        for name, alias_icon in [('OrangeLauncherAlias', 'ic_launcher'), ('OpenOrangeLauncherAlias', 'ic_launcher_open'),
                                 ('BlueLauncherAlias', 'ic_launcher_nightly'), ('OpenBlueLauncherAlias', 'ic_launcher_nightly_open')]:
            ET.SubElement(manifest.find('application'), 'activity-alias', {
                variants.ANDROID + 'name': 'rocks.pkb.plektra.' + name,
                variants.ANDROID + 'enabled': str(name == enabled).lower(),
                variants.ANDROID + 'label': label,
                variants.ANDROID + 'icon': '@mipmap/' + alias_icon,
                variants.ANDROID + 'roundIcon': '@mipmap/' + alias_icon + '_round',
                variants.ANDROID + 'targetActivity': 'rocks.pkb.plektra.MainActivity'})
        return config, manifest

    def test_configuration_manifest_and_overlay_regressions_fail(self):
        config, manifest = self.fixture()
        variants.check_contract(config, manifest, 'stable', 'release')
        for field, value in [('ENABLE_GITHUB_UPDATE_CHECKS', True), ('APPLICATION_ID', 'wrong'),
                             ('APPLICATION_ID', 'rocks.pkb.plektra.djb'),
                             ('ENABLE_ACCESSIBILITY_SERVICE', False), ('IS_FDROID_BUILD', False)]:
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                variants.check_contract(dict(config, **{field: value}), manifest, 'stable', 'release')
        for mutation in ('package', 'djb_package', 'app_label_djb', 'ime_label_djb', 'alias_label_djb',
                         'djb_shizuku', 'djb_fileprovider', 'missing_provider',
                         'version', 'accessibility', 'alias'):
            config, manifest = self.fixture()
            if mutation == 'package':
                manifest.set('package', 'wrong')
            elif mutation == 'djb_package':
                manifest.set('package', 'rocks.pkb.plektra.djb')
            elif mutation == 'app_label_djb':
                manifest.find('application').set(variants.ANDROID + 'label', 'Plektra djb')
            elif mutation == 'ime_label_djb':
                manifest.findall('application/service')[0].set(variants.ANDROID + 'label', 'Plektra djb')
            elif mutation == 'alias_label_djb':
                manifest.find('application/activity-alias').set(variants.ANDROID + 'label', 'Plektra djb')
            elif mutation == 'djb_shizuku':
                manifest.findall('application/provider')[0].set(variants.ANDROID + 'authorities', 'rocks.pkb.plektra.djb.shizuku')
            elif mutation == 'djb_fileprovider':
                manifest.findall('application/provider')[1].set(variants.ANDROID + 'authorities', 'rocks.pkb.plektra.djb.fileprovider')
            elif mutation == 'missing_provider':
                manifest.find('application').remove(manifest.findall('application/provider')[1])
            elif mutation == 'version':
                manifest.set(variants.ANDROID + 'versionCode', '1')
            elif mutation == 'accessibility':
                manifest.find('application').remove(manifest.findall('application/service')[1])
            else:
                manifest.find('application/activity-alias').set(variants.ANDROID + 'enabled', 'false')
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                variants.check_contract(config, manifest, 'stable', 'release')

    def test_all_twelve_variants_and_fork_debug_contract(self):
        self.assertEqual(12, len(self.EXPECTED_VARIANTS))
        for (flavor, build_type), _expected in self.EXPECTED_VARIANTS.items():
            for fdroid in (True, False):
                config, manifest = self.fixture(flavor, build_type, fdroid=fdroid)
                with self.subTest(flavor=flavor, build_type=build_type, fdroid=fdroid):
                    variants.check_contract(config, manifest, flavor, build_type, fdroid=fdroid)
        # Non-fdroid mode still requires GitHub update checks on direct builds when requested explicitly.
        non_fdroid_config, non_fdroid_manifest = self.fixture('stable', 'release', fdroid=False)
        with self.assertRaises(ValueError):
            variants.check_contract(dict(non_fdroid_config, ENABLE_GITHUB_UPDATE_CHECKS=False),
                                    non_fdroid_manifest, 'stable', 'release', fdroid=False)
        # Play flavors stay IS_FDROID_BUILD=False even when fdroid=True.
        play_config, play_manifest = self.fixture('playStable', 'release', fdroid=True)
        with self.assertRaises(ValueError):
            variants.check_contract(dict(play_config, IS_FDROID_BUILD=True),
                                    play_manifest, 'playStable', 'release', fdroid=True)
        # Upstream (un-suffixed) package, label, or provider authority must fail for fork debug builds.
        config, manifest = self.fixture('stable', 'debug')
        with self.assertRaises(ValueError):
            variants.check_contract(dict(config, APPLICATION_ID='rocks.pkb.plektra'), manifest, 'stable', 'debug')
        for mutation in ('app_label', 'ime_label', 'alias_label', 'shizuku_authority', 'fileprovider_authority'):
            config, manifest = self.fixture('stable', 'debug')
            if mutation == 'app_label':
                manifest.find('application').set(variants.ANDROID + 'label', 'Plektra')
            elif mutation == 'ime_label':
                manifest.findall('application/service')[0].set(variants.ANDROID + 'label', 'Plektra')
            elif mutation == 'alias_label':
                manifest.find('application/activity-alias').set(variants.ANDROID + 'label', 'Plektra')
            elif mutation == 'shizuku_authority':
                manifest.findall('application/provider')[0].set(variants.ANDROID + 'authorities', 'rocks.pkb.plektra.shizuku')
            else:
                manifest.findall('application/provider')[1].set(variants.ANDROID + 'authorities', 'rocks.pkb.plektra.fileprovider')
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                variants.check_contract(config, manifest, 'stable', 'debug')

    def test_main_enforces_fork_fdroid_default_and_explicit_overrides(self):
        import xml.etree.ElementTree as ET

        def write_generated(root, fdroid):
            for flavor in variants.FLAVORS:
                for kind in ('debug', 'release'):
                    config, manifest = self.fixture(flavor, kind, fdroid=fdroid)
                    cfg_path = root / f'app/build/generated/source/buildConfig/{flavor}/{kind}/rocks/pkb/plektra/BuildConfig.java'
                    cfg_path.parent.mkdir(parents=True, exist_ok=True)
                    lines = []
                    for key, value in config.items():
                        if isinstance(value, bool):
                            lines.append(f'public static final boolean {key} = {str(value).lower()};')
                        elif isinstance(value, int):
                            lines.append(f'public static final int {key} = {value};')
                        else:
                            lines.append(f'public static final String {key} = "{value}";')
                    cfg_path.write_text('\n'.join(lines))
                    variant = flavor + kind.title()
                    manifest_path = (
                        root / f'app/build/intermediates/merged_manifest/{variant}'
                        f'/process{variant[0].upper() + variant[1:]}MainManifest/AndroidManifest.xml'
                    )
                    manifest_path.parent.mkdir(parents=True, exist_ok=True)
                    ET.ElementTree(manifest).write(manifest_path, encoding='utf-8', xml_declaration=True)

        for argv, expect_fdroid, expect_flag in (
            (['ci-variants.py'], True, None),
            (['ci-variants.py', '--fdroid'], True, '-PPLEKTRA_FDROID_BUILD=true'),
            (['ci-variants.py', '--gradle-arg=-PPLEKTRA_FDROID_BUILD=false'], False, '-PPLEKTRA_FDROID_BUILD=false'),
        ):
            with self.subTest(argv=argv), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                write_generated(root, expect_fdroid)
                with patch.object(variants, 'ROOT', root), \
                        patch.object(variants.subprocess, 'run') as run, \
                        patch('sys.argv', argv):
                    variants.main()
                command = run.call_args[0][0]
                if expect_flag is None:
                    self.assertFalse(any('PLEKTRA_FDROID_BUILD' in arg for arg in command))
                else:
                    self.assertIn(expect_flag, command)


if __name__ == '__main__':
    unittest.main()
