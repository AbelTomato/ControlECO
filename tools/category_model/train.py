"""Privacy-conscious data audit utilities for the local category model."""

import argparse
import csv
import hashlib
import json
import math
import random
import re
import unicodedata
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from sklearn.feature_extraction.text import CountVectorizer
from sklearn.linear_model import LogisticRegression


UNKNOWN_LABELS = {"未知", "unknown"}
MULTI_LABEL_DELIMITERS = re.compile(r"[,，、;；|]")


@dataclass(frozen=True)
class Model:
    labels: tuple
    vocabulary: dict
    coefficients: tuple
    intercepts: tuple
    ngram_min: int
    ngram_max: int


def normalize_text(value):
    value = unicodedata.normalize("NFKC", str(value or "")).casefold()
    value = re.sub(r"\d+", "#", value)
    return " ".join(value.split())


def normalize_group(example):
    merchant = normalize_text(example.get("merchant", ""))
    text = normalize_text(example.get("text", ""))
    return merchant or text


def _is_expense(value):
    normalized = unicodedata.normalize("NFKC", str(value or "")).strip().casefold()
    return normalized in {"expense", "支出", "outcome", "消费"}


def _is_enabled(value):
    return str(value or "").strip().casefold() not in {"0", "false", "no", "否", "n"}


def _is_multi_label(label):
    return bool(MULTI_LABEL_DELIMITERS.search(label))


def load_examples(path=None, *, rows=None):
    """Return redacted expense examples and aggregate exclusion counts.

    ``rows`` is a list of CSV value rows, useful for unit tests. File input is
    consumed using the known candidate CSV header; sample content is never
    emitted or persisted by this module.
    """
    if rows is None:
        if path is None:
            raise ValueError("path is required when rows are not provided")
        with Path(path).open("r", encoding="utf-8-sig", newline="") as source:
            reader = csv.DictReader(source)
            records = list(reader)
    else:
        records = []
        for row in rows:
            if isinstance(row, dict):
                records.append(row)
            else:
                if len(row) < 7:
                    raise ValueError("candidate CSV row has fewer than 7 columns")
                records.append(dict(zip(
                    ("sample_id", "source", "text", "direction_label",
                     "category_label", "use_for_direction", "use_for_category"),
                    row,
                )))

    exclusions = Counter()
    examples = []
    for record in records:
        label = str(record.get("category_label") or "").strip()
        direction = record.get("direction_label", "")
        text = normalize_text(record.get("text", ""))

        if label.casefold() in {item.casefold() for item in UNKNOWN_LABELS}:
            exclusions["unknown_label"] += 1
            continue
        if not label:
            exclusions["blank_label"] += 1
            continue
        if _is_multi_label(label):
            exclusions["multi_label"] += 1
            continue
        if not _is_expense(direction):
            exclusions["non_expense"] += 1
            continue
        if not _is_enabled(record.get("use_for_category", "true")):
            exclusions["not_category_candidate"] += 1
            continue
        if not text:
            exclusions["empty_text"] += 1
            continue

        examples.append({
            "text": text,
            "merchant": normalize_text(record.get("merchant", "")),
            "label": label,
        })
    return examples, dict(exclusions)


def split_by_group(examples, seed):
    """Deterministically hold out approximately 20% of normalized groups."""
    groups = defaultdict(list)
    for example in examples:
        groups[normalize_group(example)].append(example)
    keys = sorted(groups)
    if len(keys) < 2:
        return list(examples), []
    shuffled = list(keys)
    random.Random(seed).shuffle(shuffled)
    test_count = max(1, round(len(shuffled) * 0.2))
    test_keys = set(shuffled[:test_count])
    train = [item for key in keys if key not in test_keys for item in groups[key]]
    test = [item for key in keys if key in test_keys for item in groups[key]]
    return train, test


def evaluate(y_true, y_pred, labels=None):
    if len(y_true) != len(y_pred):
        raise ValueError("y_true and y_pred must have equal lengths")
    labels = sorted(set(y_true) | set(y_pred)) if labels is None else list(labels)
    total = len(y_true)
    correct = sum(actual == predicted for actual, predicted in zip(y_true, y_pred))
    per_label = {}
    f1_values = []
    weighted_f1 = 0.0
    for label in labels:
        tp = sum(actual == label and predicted == label for actual, predicted in zip(y_true, y_pred))
        fp = sum(actual != label and predicted == label for actual, predicted in zip(y_true, y_pred))
        support = sum(actual == label for actual in y_true)
        fn = support - tp
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / support if support else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_label[label] = {
            "precision": precision,
            "recall": recall,
            "support": support,
        }
        if support:
            f1_values.append(f1)
            weighted_f1 += f1 * support
    return {
        "accuracy": correct / total if total else 0.0,
        "macro_f1": sum(f1_values) / len(f1_values) if f1_values else 0.0,
        "weighted_f1": weighted_f1 / total if total else 0.0,
        "per_label": per_label,
    }


def calibration_report(y_true, probabilities, labels, bins=10):
    """Return aggregate multiclass calibration error without retaining samples."""
    if len(y_true) != len(probabilities):
        raise ValueError("y_true and probabilities must have equal lengths")
    if not y_true:
        return {"count": 0, "expected_calibration_error": 0.0}
    buckets = {}
    for actual, scores in zip(y_true, probabilities):
        confidence = max(scores) if scores else 0.0
        predicted = labels[max(range(len(scores)), key=scores.__getitem__)] if scores else None
        bucket = min(int(confidence * bins), bins - 1)
        bucket_values = buckets.setdefault(bucket, [0, 0.0, 0.0])
        bucket_values[0] += 1
        bucket_values[1] += confidence
        bucket_values[2] += float(predicted == actual)
    errors = 0.0
    for count, confidence_sum, accuracy_sum in buckets.values():
        errors += count / len(y_true) * abs(confidence_sum / count - accuracy_sum / count)
    return {"count": len(y_true), "expected_calibration_error": errors}


def train_classifier(examples, ngram_min=2, ngram_max=4, regularization=1.0):
    if not examples:
        raise ValueError("examples must not be empty")
    if ngram_min < 1 or ngram_max < ngram_min or regularization <= 0:
        raise ValueError("invalid n-gram range or regularization")
    labels = tuple(sorted({item["label"] for item in examples}))
    if len(labels) < 2:
        raise ValueError("at least two labels are required")
    vectorizer = CountVectorizer(analyzer="char", ngram_range=(ngram_min, ngram_max), binary=True)
    features = vectorizer.fit_transform([item["text"] for item in examples])
    classifier = LogisticRegression(
        C=1.0 / regularization,
        class_weight="balanced",
        max_iter=2000,
        random_state=42,
        solver="lbfgs",
    )
    classifier.fit(features, [item["label"] for item in examples])
    return Model(
        labels=tuple(str(label) for label in classifier.classes_),
        vocabulary=dict(vectorizer.vocabulary_),
        coefficients=tuple(tuple(float(value) for value in row) for row in classifier.coef_),
        intercepts=tuple(float(value) for value in classifier.intercept_),
        ngram_min=ngram_min,
        ngram_max=ngram_max,
    )


def _model_probabilities(model, text):
    normalized = normalize_text(text)
    features = Counter(
        normalized[start:start + size]
        for size in range(model.ngram_min, model.ngram_max + 1)
        for start in range(max(0, len(normalized) - size + 1))
    )
    logits = []
    for weights, intercept in zip(model.coefficients, model.intercepts):
        score = intercept
        for feature in features:
            index = model.vocabulary.get(feature)
            if index is not None:
                score += weights[index]
        logits.append(score)
    if len(model.labels) == 2 and len(logits) == 1:
        logits = [-logits[0] / 2.0, logits[0] / 2.0]
    maximum = max(logits)
    exponentials = [math.exp(max(-700.0, value - maximum)) for value in logits]
    denominator = sum(exponentials)
    return [value / denominator for value in exponentials]


def predict(model, text):
    probabilities = _model_probabilities(model, text)
    return sorted(zip(model.labels, probabilities), key=lambda item: (-item[1], item[0]))


def split_three_by_group(examples, seed=42):
    """Split normalized groups into train/validation/test without overlap."""
    groups = defaultdict(list)
    for example in examples:
        groups[normalize_group(example)].append(example)
    keys = sorted(groups)
    random.Random(seed).shuffle(keys)
    if len(keys) < 5:
        raise ValueError("at least five independent groups are required")
    test_count = max(1, round(len(keys) * 0.2))
    validation_count = max(1, round(len(keys) * 0.2))
    test_keys = set(keys[:test_count])
    validation_keys = set(keys[test_count:test_count + validation_count])
    train = [item for key in keys if key not in test_keys | validation_keys for item in groups[key]]
    validation = [item for key in keys if key in validation_keys for item in groups[key]]
    test = [item for key in keys if key in test_keys for item in groups[key]]
    return train, validation, test


_RULES = (
    ("餐饮", ("餐饮", "外卖", "美团", "饿了么", "肯德基", "麦当劳", "星巴克", "瑞幸", "coffee", "奶茶", "咖啡", "小吃", "快餐", "餐", "食堂", "食品")),
    ("交通", ("交通", "打车", "滴滴", "高德", "地铁", "公交", "出行", "加油", "高铁", "火车", "机票", "12306", "单车", "货拉拉", "停车", "乘车")),
    ("购物", ("淘宝", "京东", "拼多多", "天猫", "购物", "商城", "抖音商城", "唯品会")),
    ("日用", ("日用", "超市", "便利店", "百货")),
    ("居住", ("房租", "水电", "电费", "燃气", "物业", "话费", "宽带", "充值中心", "供暖")),
    ("娱乐", ("娱乐", "游戏", "电影", "视频", "音乐", "会员", "票务", "演出", "ktv")),
    ("医疗", ("医疗", "药房", "药店", "医院", "医务室", "卫生院", "门诊", "挂号", "诊所", "体检")),
    ("工资", ("工资", "薪酬", "薪", "奖金")),
    ("理财", ("理财", "基金", "收益", "利息", "余额宝", "零钱通", "赎回", "分红")),
    ("红包", ("红包", "转账", "收款码", "赞赏")),
)
_RULE_LABEL_ALIASES = {"美食": "餐饮"}


def rule_guess(text, labels):
    """Mirror the existing CategoryGuesser order; return only supported labels."""
    predicted = next((label for label, keywords in _RULES
                      if any(keyword in text for keyword in keywords)), "其他")
    predicted = _RULE_LABEL_ALIASES.get(predicted, predicted)
    return predicted if predicted in labels else None


def _predict_probabilities(model, texts):
    return [[dict(predict(model, text)).get(label, 0.0) for label in model.labels]
            for text in texts]


def _metrics_for_comparable_labels(actual, predictions, labels):
    selected = [(truth, prediction) for truth, prediction in zip(actual, predictions)
                if truth in labels]
    if not selected:
        return {"count": 0, **evaluate([], [])}
    return {"count": len(selected), **evaluate(
        [truth for truth, _ in selected], [prediction for _, prediction in selected]
    )}


def train_and_evaluate(examples, seed=42):
    train, validation, test = split_three_by_group(examples, seed)
    if len({item["label"] for item in train}) < 2:
        raise ValueError("training partition must contain at least two labels")
    candidates = []
    for ngram_min, ngram_max, regularization in ((2, 4, 0.1), (2, 4, 1.0), (2, 4, 10.0),
                                                   (1, 3, 0.1), (1, 3, 1.0), (1, 3, 10.0)):
        model = train_classifier(train, ngram_min, ngram_max, regularization)
        validation_predictions = [predict(model, item["text"])[0][0] for item in validation]
        candidates.append((evaluate([item["label"] for item in validation], validation_predictions)["macro_f1"],
                           ngram_min, ngram_max, regularization))
    _, ngram_min, ngram_max, regularization = max(candidates, key=lambda item: (item[0], -item[1], -item[2], -item[3]))
    fitted_examples = train + validation
    model = train_classifier(fitted_examples, ngram_min, ngram_max, regularization)
    y_true = [item["label"] for item in test]
    model_probabilities = _predict_probabilities(model, [item["text"] for item in test])
    model_predictions = [model.labels[max(range(len(scores)), key=scores.__getitem__)]
                         for scores in model_probabilities]
    label_groups = defaultdict(set)
    for item in test:
        label_groups[item["label"]].add(normalize_group(item))
    evaluable = sorted(label for label in {item["label"] for item in examples}
                       if len(label_groups[label]) >= 2)
    evaluable_indices = [index for index, label in enumerate(y_true) if label in evaluable]
    evaluated_truth = [y_true[index] for index in evaluable_indices]
    evaluated_predictions = [model_predictions[index] for index in evaluable_indices]
    comparison_indices = evaluable_indices
    comparison_truth = [y_true[index] for index in comparison_indices]
    rule_predictions = [rule_guess(item["text"], model.labels) for item in test]
    model_comparable = [model_predictions[index] for index in comparison_indices]
    rule_comparable = [rule_predictions[index] or "__unmatched_rule__"
                       for index in comparison_indices]
    comparison_labels = sorted(set(comparison_truth))
    report = {
        "seed": seed,
        "split": {"train_examples": len(train), "validation_examples": len(validation),
                  "test_examples": len(test),
                  "train_groups": len({normalize_group(item) for item in train}),
                  "validation_groups": len({normalize_group(item) for item in validation}),
                  "test_groups": len({normalize_group(item) for item in test})},
        "selected_parameters": {"ngram_min": ngram_min, "ngram_max": ngram_max,
                                "regularization": regularization},
        "validation_candidates": [{"macro_f1": score, "ngram_min": low, "ngram_max": high,
                                   "regularization": strength}
                                  for score, low, high, strength in candidates],
        "test_metrics": {"evaluated_examples": len(evaluable_indices),
                         **evaluate(evaluated_truth, evaluated_predictions)},
        "test_calibration": calibration_report(y_true, model_probabilities, model.labels),
        "evaluable_labels": evaluable,
        "unevaluable_labels": sorted(set(item["label"] for item in examples) - set(evaluable)),
        "rule_baseline": {"comparable_count": len(comparison_truth),
                          "metrics": evaluate(comparison_truth, rule_comparable,
                                              labels=comparison_labels)},
        "model_on_rule_comparable": evaluate(comparison_truth, model_comparable,
                                              labels=comparison_labels),
        "metrics_meaningful": len(evaluable) >= 2 and len(comparison_truth) >= 10,
        "merchant_field_available": any(item.get("merchant") for item in examples),
        "raw_text_in_report": False,
    }
    return model, report


def export_model(model, path, metadata=None):
    artifact = {
        "schema_version": 1,
        "labels": list(model.labels),
        "ngram_min": model.ngram_min,
        "ngram_max": model.ngram_max,
        "vocabulary": {feature: index for feature, index in model.vocabulary.items()},
        "weights": [list(row) for row in model.coefficients],
        "intercepts": list(model.intercepts),
        "thresholds": {"confidence": 0.6, "margin": 0.15},
        "metadata": {key: value for key, value in (metadata or {}).items()
                     if key in {"seed", "ngram_min", "ngram_max", "regularization",
                                "test_accuracy", "test_macro_f1", "test_weighted_f1"}},
    }
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(artifact, ensure_ascii=False, separators=(",", ":")) + "\n",
                           encoding="utf-8")


def audit(path, output_dir, seed=42):
    examples, exclusions = load_examples(path)
    train, test = split_by_group(examples, seed)
    label_counts = Counter(item["label"] for item in examples)
    train_labels = Counter(item["label"] for item in train)
    test_labels = Counter(item["label"] for item in test)
    test_label_groups = defaultdict(set)
    for item in test:
        test_label_groups[item["label"]].add(normalize_group(item))
    evaluable = sorted(label for label in label_counts if len(test_label_groups[label]) >= 2)
    unevaluable = sorted(label for label in label_counts if len(test_label_groups[label]) < 2)
    report = {
        "input_file_name": Path(path).name,
        "expense_examples": len(examples),
        "excluded_examples": sum(exclusions.values()),
        "exclusions": dict(sorted(exclusions.items())),
        "train_examples": len(train),
        "test_examples": len(test),
        "train_groups": len({normalize_group(item) for item in train}),
        "test_groups": len({normalize_group(item) for item in test}),
        "label_counts": dict(sorted(label_counts.items())),
        "train_label_counts": dict(sorted(train_labels.items())),
        "test_label_counts": dict(sorted(test_labels.items())),
        "evaluable_labels": evaluable,
        "unevaluable_labels": unevaluable,
        "metrics_meaningful": len(evaluable) >= 2,
        "test_label_group_counts": {
            label: len(test_label_groups[label]) for label in sorted(label_counts)
        },
        "note": "No classifier or rule-baseline predictions were produced in Task 1; predictive metrics are not reported.",
    }
    destination = Path(output_dir)
    destination.mkdir(parents=True, exist_ok=True)
    report_path = destination / "audit_report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return report


def main():
    parser = argparse.ArgumentParser(description="Audit local expense category training data")
    parser.add_argument("csv_path")
    parser.add_argument("output_dir")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--train", action="store_true", help="train and evaluate an expense classifier")
    args = parser.parse_args()
    if not args.train:
        audit(args.csv_path, args.output_dir, args.seed)
        return
    source_sha256 = hashlib.sha256(Path(args.csv_path).read_bytes()).hexdigest()
    examples, exclusions = load_examples(args.csv_path)
    model, report = train_and_evaluate(examples, args.seed)
    report["expense_examples"] = len(examples)
    report["excluded_examples"] = sum(exclusions.values())
    report["exclusions"] = dict(sorted(exclusions.items()))
    report["input_sha256"] = source_sha256
    report["dependency_versions"] = {
        "scikit_learn": __import__("sklearn").__version__,
    }
    destination = Path(args.output_dir)
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "training_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    baseline = report["rule_baseline"]["metrics"]
    candidate = report["model_on_rule_comparable"]
    if report["metrics_meaningful"] and candidate["macro_f1"] > baseline["macro_f1"]:
        export_model(model, destination / "category_model.json", {
            "seed": args.seed,
            **report["selected_parameters"],
            "test_accuracy": report["test_metrics"]["accuracy"],
            "test_macro_f1": report["test_metrics"]["macro_f1"],
            "test_weighted_f1": report["test_metrics"]["weighted_f1"],
        })
        print("Model exported: grouped held-out comparable macro-F1 exceeds rule baseline.")
    else:
        print("Model not exported: evaluation is not meaningful or does not beat rule baseline.")


if __name__ == "__main__":
    main()