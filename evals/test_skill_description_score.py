import json
from pathlib import Path
import unittest
from skill_description_score import score_description


class DescriptionScoreTest(unittest.TestCase):
    def setUp(self):
        self.reference = json.loads((Path(__file__).parent/'cases/description/example.json').read_text())

    def test_reference_passes_initial_checks_but_requires_manual_review(self):
        result = score_description(json.dumps(self.reference, ensure_ascii=False))
        self.assertEqual('passed', result['automatic_status'])
        self.assertEqual('pending', result['manual_review'])

    def test_invalid_fenced_extra_and_duplicate_json_are_rejected(self):
        for text in ['```json\n{}\n```', '{"description":"x","description":"y","detail_description":"z"}',
                     json.dumps(dict(self.reference, price=1)), '{"description":null,"detail_description":"x"}']:
            self.assertEqual('failed', score_description(text)['automatic_status'])

    def test_source_notes_and_parameter_table_fail(self):
        for bad in ['官网介绍', '本记录原为旧条目', '| 参数 | 数值 |', '负载：3kg；工作半径：600mm']:
            self.reference['detail_description'] = bad
            self.assertEqual('failed', score_description(json.dumps(self.reference))['automatic_status'])

    def test_paraphrase_misses_are_review_not_false_semantic_failure(self):
        self.reference['detail_description'] = '用于课程中的抓取演练。'
        result = score_description(json.dumps(self.reference))
        self.assertEqual('needs_review', result['automatic_status'])


if __name__ == '__main__':
    unittest.main()
