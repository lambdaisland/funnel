# Unreleased

## Added

## Fixed

## Changed

# 1.4.71 (2021-12-16 / 6ae91b0)

## Added

## Fixed

## Changed

# 0.1.42 (2020-08-26 / 4c14cec)

## Added

- Added a `--daemonize` flag so Funnel can background itself (experimental)
- Added a `--logfile FILE` option to redirect output
- Added a `--ws-port PORT` options
- Added a `--wss-port PORT` option
- Added a `--version` flag

## Changed

- No longer include a default certificate
- Only start WSS server when a certificate is provided
- Changed the default `--keystore-password` from `"funnel"` to `"password"`
  (same as [bhauman/certifiable](https://github.com/bhauman/certifiable))

## Fixed

- Correctly format log messages that contain parameters (like jdk.event.security)

# 0.1.16 (2020-05-26 / 81b2e61)

## Added

- First prerelease version, implements `:funnel/whoami`, `:funnel/subscribe`,
  `:funnel/unsubscribe`, `:funnel/broadcast`, `:funnel/query`.
- Selectors: `true`, vector, map.