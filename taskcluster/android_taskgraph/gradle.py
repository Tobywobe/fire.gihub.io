# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.


import json
import subprocess

from taskgraph.util.memoize import memoize

from android_taskgraph import FOCUS_DIR


def get_variant(build_type, build_name):
    all_variants = _fetch_all_variants()
    matching_variants = [
        variant for variant in all_variants
        if variant["build_type"] == build_type and variant["name"] == build_name
    ]
    number_of_matching_variants = len(matching_variants)
    if number_of_matching_variants == 0:
        raise ValueError('No variant found for build type "{}"'.format(
            build_type
        ))
    elif number_of_matching_variants > 1:
        raise ValueError('Too many variants found for build type "{}"": {}'.format(
            build_type, matching_variants
        ))

    return matching_variants.pop()


@memoize
def _fetch_all_variants():
    output = _run_gradle_process('printVariants')
    content = _extract_content_from_command_output(output, prefix='variants: ')
    return json.loads(content)


def _run_gradle_process(gradle_command, **kwargs):
    gradle_properties = [
        f'-P{property_name}={value}'
        for property_name, value in kwargs.items()
    ]
    process = subprocess.Popen(
        ["./gradlew", "--no-daemon", "--quiet", gradle_command] + gradle_properties,
        stdout=subprocess.PIPE,
        universal_newlines=True,
        cwd=FOCUS_DIR,
    )
    output, err = process.communicate()
    exit_code = process.wait()

    if exit_code != 0:
        raise RuntimeError(f"Gradle command returned error: {exit_code}")

    return output


def _extract_content_from_command_output(output, prefix):
    variants_line = [line for line in output.split('\n') if line.startswith(prefix)][0]
    return variants_line.split(' ', 1)[1]
