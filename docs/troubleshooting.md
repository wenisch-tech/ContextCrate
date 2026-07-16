# Troubleshooting

## Startup rejects the backend combination

Local queue and H2 require `harvex.role=all`. Use RabbitMQ and PostgreSQL for separate roles. Lucene requires one writer.

## URLs are blocked

Harvex blocks non-public network ranges by default. Verify DNS and redirects. For an authorized intranet crawl, explicitly enable private networks and constrain allowed hosts.

## Work remains processing after a crash

Wait for the configured lease duration. The local queue makes stale leases claimable automatically. Inspect `/operations` and Prometheus metrics for repeated failures.

## Browser fetches fail

Use the browser image, which contains Chromium and its OS dependencies. The normal image intentionally does not install a browser.

## Index differs after migration

Restore queues a complete rebuild. Wait for index queue depth to reach zero and compare normalized-document and index counts.
