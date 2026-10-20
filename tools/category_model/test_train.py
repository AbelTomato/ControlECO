import unittest
import json
import tempfile
from pathlib import Path

from tools.category_model.train import (
    evaluate,
    export_model,
    load_examples,
    normalize_group,
    normalize_text,
    predict,
    split_by_group,
    train_classifier,
    calibration_report,
    rule_guess,
    split_three_by_group,
    train_and_evaluate,
)


class LoadExamplesTests(unittest.TestCase):
    def test_filters_rows_and_redacts_text(self):
        rows = [
            ["1", "bank", "Cafe 1234", "expense", "Food", "false", "true"],
            ["2", "bank", "Cafe", "expense", " 未知 ", "false", "true"],
            ["3", "bank", "Shop", "expense", "", "false", "true"],
            ["4", "bank", "Shop", "expense", "Food,Travel", "false", "true"],
            ["5", "bank", "Salary", "income", "Income", "false", "true"],
            ["6", "bank", "Transfer", "non-expense", "Transfer", "false", "true"],
            ["7", "bank", "   ", "expense", "Food", "false", "true"],
            ["8", "bank", "Cafe 5678", "expense", "Food", "false", "true"],
        ]

        examples, exclusions = load_examples(rows=rows)

        self.assertEqual(["Food", "Food"], [item["label"] for item in examples])
        self.assertEqual("cafe #", examples[0]["text"])
        self.assertEqual(6, sum(exclusions.values()))
        self.assertEqual(1, exclusions["unknown_label"])
        self.assertEqual(1, exclusions["blank_label"])
        self.assertEqual(1, exclusions["multi_label"])
        self.assertEqual(2, exclusions["non_expense"])
        self.assertEqual(1, exclusions["empty_text"])

    def test_supports_category_use_flag(self):
        rows = [["1", "bank", "Cafe", "expense", "Food", "false", "false"]]
        examples, exclusions = load_examples(rows=rows)
        self.assertEqual([], examples)
        self.assertEqual(1, exclusions["not_category_candidate"])


class GroupingTests(unittest.TestCase):
    def test_normalized_duplicate_text_stays_in_one_partition(self):
        examples = [
            {"text": "Cafe 123", "merchant": "Cafe", "label": "Food"},
            {"text": " cafe 456 ", "merchant": "CAFE", "label": "Food"},
            {"text": "Bookshop", "merchant": "Books", "label": "Shopping"},
            {"text": "Market", "merchant": "Market", "label": "Food"},
        ]
        train, test = split_by_group(examples, seed=42)
        train_groups = {normalize_group(item) for item in train}
        test_groups = {normalize_group(item) for item in test}
        self.assertTrue(train)
        self.assertTrue(test)
        self.assertFalse(train_groups & test_groups)

    def test_normalization_redacts_digits_and_collapses_spacing(self):
        self.assertEqual("cafe #", normalize_text("  Cafe   1234 "))


class EvaluationTests(unittest.TestCase):
    def test_empty_predictions_have_zero_metrics(self):
        metrics = evaluate([], [])
        self.assertEqual(0.0, metrics["accuracy"])
        self.assertEqual(0.0, metrics["macro_f1"])
        self.assertEqual(0.0, metrics["weighted_f1"])

    def test_single_support_label_and_zero_division(self):
        metrics = evaluate(["Food"], ["Shopping"])
        self.assertEqual(0.0, metrics["accuracy"])
        self.assertEqual(0.0, metrics["per_label"]["Food"]["precision"])
        self.assertEqual(0.0, metrics["per_label"]["Food"]["recall"])
        self.assertEqual(1, metrics["per_label"]["Food"]["support"])


class ClassifierTests(unittest.TestCase):
    def setUp(self):
        self.examples = [
            {"text": "咖啡拿铁", "merchant": "咖啡店", "label": "餐饮"},
            {"text": "美式咖啡", "merchant": "咖啡铺", "label": "餐饮"},
            {"text": "公交地铁", "merchant": "公交站", "label": "交通"},
            {"text": "地铁乘车", "merchant": "地铁口", "label": "交通"},
        ]

    def test_training_is_deterministic_and_probabilities_are_ordered(self):
        first = train_classifier(self.examples, ngram_min=1, ngram_max=2, regularization=1.0)
        second = train_classifier(self.examples, ngram_min=1, ngram_max=2, regularization=1.0)

        self.assertEqual(first, second)
        predictions = predict(first, "咖啡拿铁")
        self.assertEqual("餐饮", predictions[0][0])
        self.assertEqual(sorted((score for _, score in predictions), reverse=True),
                         [score for _, score in predictions])
        self.assertAlmostEqual(1.0, sum(score for _, score in predictions), places=7)

    def test_unseen_ngrams_return_valid_normalized_scores(self):
        model = train_classifier(self.examples, ngram_min=2, ngram_max=3, regularization=1.0)
        predictions = predict(model, "完全陌生文本")

        self.assertEqual(set(model.labels), {label for label, _ in predictions})
        self.assertTrue(all(0.0 <= score <= 1.0 for _, score in predictions))
        self.assertAlmostEqual(1.0, sum(score for _, score in predictions), places=7)

    def test_calibration_report_is_aggregate_and_handles_empty_input(self):
        probabilities = [[0.9, 0.1], [0.3, 0.7]]
        report = calibration_report(["A", "A"], probabilities, ["A", "B"])

        self.assertEqual(2, report["count"])
        self.assertAlmostEqual(0.4, report["expected_calibration_error"], places=7)
        self.assertEqual({"count": 0, "expected_calibration_error": 0.0},
                         calibration_report([], [], ["A", "B"]))

    def test_export_contains_no_training_text(self):
        model = train_classifier(self.examples, ngram_min=1, ngram_max=2, regularization=1.0)
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "category_model.json"
            export_model(model, target, {"seed": 42, "threshold": 0.6})
            serialized = target.read_text(encoding="utf-8")
            artifact = json.loads(serialized)

        self.assertEqual(1, artifact["schema_version"])
        self.assertEqual(["交通", "餐饮"], artifact["labels"])
        self.assertNotIn("咖啡拿铁", serialized)
        self.assertNotIn("美式咖啡", serialized)
        self.assertIn("weights", artifact)
        self.assertIn("thresholds", artifact)

    def test_three_way_split_keeps_groups_disjoint(self):
        examples = [
            {"text": f"merchant {chr(65 + index)}", "merchant": f"merchant {chr(65 + index)}",
             "label": "A" if index % 2 else "B"}
            for index in range(20)
        ]

        train, validation, test = split_three_by_group(examples, seed=11)
        group_sets = [{normalize_group(item) for item in partition}
                      for partition in (train, validation, test)]

        self.assertTrue(all(group_sets[left].isdisjoint(group_sets[right])
                            for left in range(3) for right in range(left + 1, 3)))

    def test_rule_baseline_only_reports_categories_supported_by_current_rules(self):
        self.assertEqual("餐饮", rule_guess("星巴克咖啡", ["餐饮", "交通"]))
        self.assertEqual("餐饮", rule_guess("美食外卖", ["餐饮", "美食", "交通"]))
        self.assertIsNone(rule_guess("完全无关键词", ["餐饮", "交通"]))

    def test_comparison_counts_uncovered_rule_examples_as_errors(self):
        examples = [
            {"text": f"餐饮样本{chr(65 + index)}", "merchant": f"餐饮商户{chr(65 + index)}", "label": "餐饮"}
            for index in range(12)
        ] + [
            {"text": f"独特样本{chr(65 + index)}", "merchant": f"独特商户{chr(65 + index)}", "label": "交通"}
            for index in range(8)
        ]

        _, report = train_and_evaluate(examples, seed=3)

        comparison_count = report["test_metrics"]["evaluated_examples"]
        self.assertEqual(comparison_count, report["rule_baseline"]["comparable_count"])
        self.assertLess(report["rule_baseline"]["metrics"]["accuracy"], 1.0)


if __name__ == "__main__":
    unittest.main()