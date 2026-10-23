This object exists so that tests can prove the object pattern is actually applied.

The adapter under test is configured with an object pattern such as:

    json/sensors-*.json

Objects that MUST match:
    json/sensors-2026-01-01.json
    json/sensors-2026-01-02.json
    json/sensors-2026-01-03.json

Objects that MUST NOT match:
    csv/machine-data.csv
    broken/malformed.json
    other/readme.txt            <- this file
    large/machine-data-large.csv

If this file ever shows up in an adapter's output, the pattern filter is broken.
