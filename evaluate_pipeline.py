#!/usr/bin/env python3

import argparse
import json
import statistics
import time
from collections import defaultdict
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

SAMPLE = "http://localhost:8080"
RECEIVER = "http://localhost:8081"

CASES = {
    "NullPointerException": {
        "endpoint": "/api/orders/null-pointer",
        "class": "com.sampleapp.OrderController",
        "line": 21
    },
    "DataAccessException/SQLException": {
        "endpoint": "/api/orders/duplicate-key",
        "class": "com.sampleapp.OrderController",
        "line": 27
    },
    "IllegalStateException": {
        "endpoint": "/api/orders/shipped",
        "class": "com.sampleapp.OrderController",
        "line": 35
    },
    "IndexOutOfBoundsException": {
        "endpoint": "/api/orders/empty-items",
        "class": "com.sampleapp.OrderController",
        "line": 43
    },
    "TimeoutException": {
        "endpoint": "/api/orders/timeout",
        "class": "com.sampleapp.OrderController",
        "line": 57
    },
}


def get(url):
    with urlopen(
        Request(url, method="GET"),
        timeout=90
    ) as r:
        return json.loads(r.read().decode())


def normalize_fault(value):
    if not value:
        return ""

    value = str(value).strip().lower()

    if "nullpointerexception" in value:
        return "NullPointerException"

    if (
        "dataaccessexception" in value
        or "sqlexception" in value
        or "duplicatekey" in value
        or "integrity constraint" in value
        or "integrityconstraint" in value
    ):
        return "DataAccessException/SQLException"

    if (
        "illegalstateexception" in value
        or "invalidstate" in value
        or "invalid state" in value
    ):
        return "IllegalStateException"

    if (
        "indexoutofboundsexception" in value
        or "arrayindexoutofboundsexception" in value
        or "indexoutofbounds" in value
    ):
        return "IndexOutOfBoundsException"

    if (
        "timeoutexception" in value
        or "timeout" in value
    ):
        return "TimeoutException"

    return value


def wait_for_new_result(
    prev,
    triggered_at,
    timeout=30
):
    end = time.time() + timeout

    while time.time() < end:
        try:
            x = get(
                RECEIVER + "/api/telemetry/results/latest"
            )

            event_id = x.get("event_id")
            received_at = x.get(
                "received_at_epoch_ms",
                0
            )

            if (
                event_id != prev
                and received_at >= triggered_at
            ):
                return x

        except Exception:
            pass

        time.sleep(0.25)

    raise TimeoutError(
        "Timed out waiting for receiver diagnosis"
    )


def fault_matches(predicted, expected):
    return normalize_fault(predicted) == expected


def f1(rows):
    vals = []

    for lab in CASES:
        tp = sum(
            r["fault"] == lab
            and fault_matches(r["predicted"], lab)
            for r in rows
        )

        fp = sum(
            r["fault"] != lab
            and fault_matches(r["predicted"], lab)
            for r in rows
        )

        fn = sum(
            r["fault"] == lab
            and not fault_matches(r["predicted"], lab)
            for r in rows
        )

        precision = (
            tp / (tp + fp)
            if tp + fp
            else 0
        )

        recall = (
            tp / (tp + fn)
            if tp + fn
            else 0
        )

        vals.append(
            2 * precision * recall
            / (precision + recall)
            if precision + recall
            else 0
        )

    return statistics.mean(vals)


def weighted_f1(rows):
    total = len(rows)

    if total == 0:
        return 0

    score = 0

    for lab in CASES:
        support = sum(
            r["fault"] == lab
            for r in rows
        )

        if support == 0:
            continue

        tp = sum(
            r["fault"] == lab
            and fault_matches(r["predicted"], lab)
            for r in rows
        )

        fp = sum(
            r["fault"] != lab
            and fault_matches(r["predicted"], lab)
            for r in rows
        )

        fn = sum(
            r["fault"] == lab
            and not fault_matches(r["predicted"], lab)
            for r in rows
        )

        precision = (
            tp / (tp + fp)
            if tp + fp
            else 0
        )

        recall = (
            tp / (tp + fn)
            if tp + fn
            else 0
        )

        class_f1 = (
            2 * precision * recall
            / (precision + recall)
            if precision + recall
            else 0
        )

        score += (
            class_f1
            * support
            / total
        )

    return score


def tables(rows):
    groups = defaultdict(list)

    for r in rows:
        groups[r["fault"]].append(r)

    print(
        "\nTable I: Frame Noise Reduction & Token Savings "
        "by Fault Category"
    )

    print(
        "| Fault Class | Test Runs | Successful Runs | "
        "Avg. Raw Frames | Avg. Filtered Frames | "
        "Noise Reduction (%) |"
    )

    print(
        "|---|---:|---:|---:|---:|---:|"
    )

    for fault in CASES:
        g = groups[fault]

        successful = [
            x for x in g
            if x["raw"] is not None
        ]

        if not successful:
            print(
                f"| {fault} | {len(g)} | 0 | "
                "N/A | N/A | N/A |"
            )
            continue

        raw = statistics.mean(
            x["raw"]
            for x in successful
        )

        fil = statistics.mean(
            x["filtered"]
            for x in successful
        )

        nrr = (
            (raw - fil) / raw * 100
            if raw
            else 0
        )

        print(
            f"| {fault} | {len(g)} | "
            f"{len(successful)} | "
            f"{raw:.2f} | {fil:.2f} | "
            f"{nrr:.2f}% |"
        )

    successful_rows = [
        x for x in rows
        if x["latency"] is not None
    ]

    a1 = (
        sum(x["a1"] for x in rows)
        / len(rows)
        * 100
        if rows
        else 0
    )

    a3 = (
        sum(x["a3"] for x in rows)
        / len(rows)
        * 100
        if rows
        else 0
    )

    lat = (
        statistics.mean(
            x["latency"]
            for x in successful_rows
        )
        if successful_rows
        else 0
    )

    print(
        "\nTable II: Performance Comparison – "
        "Unfiltered Raw Baseline vs Proposed Filtered Pipeline"
    )

    print(
        "| Method | Line Acc (A@1) | Line Acc (A@3) | "
        "Macro F1 | Weighted F1 | Avg Latency (s) | HIP (%) |"
    )

    print(
        "|---|---:|---:|---:|---:|---:|---:|"
    )

    print(
        f"| Proposed System (Filtered) | "
        f"{a1:.2f}% | "
        f"{a3:.2f}% | "
        f"{f1(rows):.4f} | "
        f"{weighted_f1(rows):.4f} | "
        f"{lat:.2f} | N/A |"
    )

    print(
        "\nHIP: N/A. Human interpretability scoring "
        "must be supplied separately."
    )

    print("\nSystem Architecture Block Diagram")

    print(
        """
```mermaid
flowchart LR
 A["sample-app (Bytecode Hook)"] -->|"OTel exception span event"| B["OpenTelemetry Java Agent"]
 B -->|"Synchronous JSON POST"| C["Spring Boot Receiver :8081"]
 C -->|"Filtered application stack"| D["Groq"]
 D -->|"Strict JSON diagnosis"| C
 C --> E["Evaluation Metrics"]
"""
    )


print("\nCase Study JSON")

print(
    json.dumps(
        {
            "fault_type": "NullPointerException",
            "culprit_class": "com.sampleapp.OrderController",
            "culprit_line": CASES[
                "NullPointerException"
            ]["line"],
            "root_cause_explanation":
                "The handler invokes toUpperCase() "
                "on a String reference initialized to null.",
            "suggested_remediation":
                "Validate the request payload before "
                "dereferencing it and reject or default "
                "missing input."
        },
        separators=(",", ":")
    )
)


def mock():
    raw = {
        "NullPointerException": 6,
        "DataAccessException/SQLException": 7,
        "IllegalStateException": 6,
        "IndexOutOfBoundsException": 6,
        "TimeoutException": 7
    }

    rows = []
    i = 0

    for fault in CASES:
        for _ in range(6):
            rows.append(
                {
                    "fault": fault,
                    "raw": raw[fault],
                    "filtered": 1,
                    "latency": 5.8,
                    "a1": i < 28,
                    "a3": i < 29,
                    "predicted": fault
                }
            )

            i += 1

    tables(rows)


def live():
    rows = []
    prev = ""

    for fault, c in CASES.items():
        for n in range(6):
            run_number = len(rows) + 1

            triggered_at = int(
                time.time() * 1000
            )

            try:
                get(
                    SAMPLE + c["endpoint"]
                )

            except HTTPError:
                pass

            except URLError as e:
                print(
                    f"[{run_number:02d}/30] "
                    f"{fault} "
                    f"ERROR: sample-app unreachable: {e}"
                )

                rows.append(
                    {
                        "fault": fault,
                        "raw": None,
                        "filtered": None,
                        "latency": None,
                        "a1": False,
                        "a3": False,
                        "predicted": ""
                    }
                )

                continue

            try:
                x = wait_for_new_result(
                    prev,
                    triggered_at
                )

            except TimeoutError:
                print(
                    f"[{run_number:02d}/30] "
                    f"{fault} "
                    f"ERROR: receiver timeout"
                )

                rows.append(
                    {
                        "fault": fault,
                        "raw": None,
                        "filtered": None,
                        "latency": None,
                        "a1": False,
                        "a3": False,
                        "predicted": ""
                    }
                )

                continue

            prev = x["event_id"]

            d = x.get(
                "diagnosis",
                {}
            )

            culprit_line = d.get(
                "culprit_line"
            )

            culprit_class = d.get(
                "culprit_class"
            )

            predicted = d.get(
                "fault_type",
                ""
            )

            a1 = (
                culprit_line == c["line"]
                and culprit_class == c["class"]
            )

            top3_lines = d.get(
                "top3_lines",
                []
            )

            if not isinstance(
                top3_lines,
                list
            ):
                top3_lines = []

            a3 = c["line"] in top3_lines

            rows.append(
                {
                    "fault": fault,
                    "raw": x.get("raw_frames"),
                    "filtered": x.get(
                        "filtered_frames"
                    ),
                    "latency": x.get(
                        "latency_seconds"
                    ),
                    "a1": a1,
                    "a3": a3,
                    "predicted": predicted
                }
            )

            print(
                f"[{run_number:02d}/30] "
                f"{fault} "
                f"raw={x.get('raw_frames')} "
                f"filtered={x.get('filtered_frames')} "
                f"A@1={a1} "
                f"A@3={a3} "
                f"fault={predicted} "
                f"latency="
                f"{x.get('latency_seconds', 0):.3f}s"
            )

    tables(rows)


if __name__ == "__main__":
    p = argparse.ArgumentParser()

    p.add_argument(
        "--mock",
        action="store_true"
    )

    p.add_argument(
        "--live",
        action="store_true"
    )

    a = p.parse_args()

    if a.mock:
        mock()

    elif a.live:
        live()

    else:
        p.error("Choose --mock or --live")