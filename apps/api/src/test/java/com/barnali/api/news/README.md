# Public news provider tests

These unit tests verify that the API returns `UNAVAILABLE` when no trusted provider is registered, caps provider results at five items, and rejects unsafe non-HTTPS URLs. They do not test a live news provider because none is configured.
