import unittest
from summarize import summarize


class ResultTests(unittest.TestCase):
    def test_run_filtering_missing_and_duplicate_receipts(self):
        events = [dict(id='a', run='test'), dict(id='b', run='test'), dict(id='c', run='other')]
        receipt = dict(kind='receipt', run='test', technique='MQTT', event_id='a', latency_valid=False,
                       notification_outcome='notification_submitted')
        duplicate = dict(receipt, notification_outcome='duplicate_suppressed')
        result = summarize(events, [receipt, duplicate, dict(receipt, run='other')], 'test', 'MQTT')
        self.assertEqual((result['received'], result['missed_at_export'], result['duplicates']), (1, 1, 1))
        self.assertEqual(result['valid_latency_samples'], 0)
        self.assertEqual(result['notification_submitted'], 1)


if __name__ == '__main__':
    unittest.main()
