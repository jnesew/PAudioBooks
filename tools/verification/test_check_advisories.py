import io
import json
import unittest
from unittest.mock import patch

from check_advisories import queries_from_inventory, query_osv


class AdvisoryCheckTest(unittest.TestCase):
    def test_inventory_is_deduplicated_and_incomplete_graphs_fail(self):
        component = {"name": "org.example:example", "version": "1.2.3"}
        self.assertEqual(1, len(queries_from_inventory({"components": [component, component], "failures": []})))
        for inventory in [{"components": [], "failures": []}, {"components": [component], "failures": ["unresolved"]}]:
            with self.assertRaises(ValueError):
                queries_from_inventory(inventory)

    def test_pagination_preserves_the_package_match(self):
        query = {"package": {"ecosystem": "Maven", "name": "org.example:example"}, "version": "1.2.3"}
        pages = [{"results": [{"vulns": [{"id": "first"}], "next_page_token": "next"}]},
                 {"results": [{"vulns": [{"id": "second"}]}]}]
        with patch("urllib.request.urlopen", side_effect=[io.BytesIO(json.dumps(page).encode()) for page in pages]) as call:
            results = query_osv([query])
            self.assertEqual(["first", "second"], [v["id"] for v in results[0]["vulns"]])
            self.assertEqual("next", json.loads(call.call_args.args[0].data)["queries"][0]["page_token"])

    def test_incomplete_response_is_not_reported_as_clean(self):
        with patch("urllib.request.urlopen", return_value=io.BytesIO(b'{"results":[]}')):
            with self.assertRaises(ValueError):
                query_osv([{"package": {"ecosystem": "Maven", "name": "org.example:example"}, "version": "1"}])
