"""Deterministic output checks; semantic/style acceptance remains manual."""
import json
import re


def score_description(text):
    checks = {}
    try:
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError('Duplicate JSON key')
                result[key] = value
            return result
        value = json.loads(text, object_pairs_hook=unique)
        checks['json_fields'] = (isinstance(value, dict) and set(value) == {'description', 'detail_description'}
                                 and all(isinstance(x, str) and x.strip() for x in value.values()))
    except (ValueError, TypeError):
        checks['json_fields'] = False
        value = None
    if not checks['json_fields']:
        return {'automatic_status': 'failed', 'checks': checks, 'parsed': value, 'manual_review': 'pending'}
    joined = '\n'.join(value.values())
    checks['no_forbidden_source_word'] = '官网' not in joined
    checks['no_internal_notes'] = not any(word in joined for word in ['本记录', 'type_id', '未采集', '历史误设', '⚠', '（注：', '(注:'])
    checks['no_parameter_table'] = not re.search(r'(?m)^\s*\|.*\|\s*$', joined)
    checks['no_parameter_list'] = len(re.findall(r'(?:自由度|负载|工作半径)\s*[:：]', joined)) < 2
    facts = {
        'six_axes': bool(re.search(r'六轴|6\s*轴|六个自由度|6\s*个?自由度', joined)),
        'teaching': bool(re.search(r'教学|教研', joined)),
        'light_handling': bool(re.search(r'轻型搬运|轻载搬运|轻量.*搬运', joined)),
        'payload': bool(re.search(r'(?:3|三)\s*(?:kg|千克|公斤)', joined, re.I)),
        'reach': bool(re.search(r'(?:600|六百)\s*(?:mm|毫米)|0\.6\s*(?:m\b|米)|60\s*(?:cm|厘米)', joined, re.I)),
        'drag_teaching': '拖拽示教' in joined or '拖动示教' in joined,
    }
    # Lexical misses can be paraphrases; flag for review rather than asserting semantic failure.
    status = 'failed' if not all(checks.values()) else 'passed' if all(facts.values()) else 'needs_review'
    return {'automatic_status': status, 'checks': checks, 'fact_initial_checks': facts, 'parsed': value,
            'manual_review': 'pending', 'manual_items': ['文风与字段定位', '固定事实的语义保留', '未知事实/数字是否编造'],
            'database_checks': 'not_applicable_offline'}
