# Funnel

Transit-over-WebSocket Message Relay

<!-- badges -->
[![CircleCI](https://circleci.com/gh/lambdaisland/funnel.svg?style=svg)](https://circleci.com/gh/lambdaisland/funnel) [![cljdoc badge](https://cljdoc.org/badge/lambdaisland/funnel)](https://cljdoc.org/d/lambdaisland/funnel) [![Clojars Project](https://img.shields.io/clojars/v/lambdaisland/funnel.svg)](https://clojars.org/lambdaisland/funnel)
<!-- /badges -->

## What is it?

Funnel is a WebSocket server. It accepts connections from multiple clients, and
then acts as a go-between, funneling messages between them. Messages can be
addressed at a specific client, or broadcasted to a selection of clients.

## What is it for?

Funnel forms a bridge between developer tooling and JavaScript runtimes. It
keeps persistent connections to runtimes so individual tools don't have to. This
is particularly relevant when the tool's process lifetime is shorter than the
lifetime of the JavaScript runtime.

To make that concrete, a test runner invoked multiple times from the command
line can run commands in the same pre-existing browser tab.

## Specifics

Funnel listens on port 44220 (without ssl) and 44221 (with ssl). The SSL port is
provided for when connecting from an HTTPS context where unencrypted connections
are not allowed. It uses a self-signed certificate. To use this from a browser,
go to `https://localhost:44221` and accept the certificate. After that websocket
connections to `wss://localhost:44221` should work.

To use your own certificate, provide a Java KeyStore with `--keystore FILENAME`,
and `--keystore-password PASSWORD`.

## License

Copyright &copy; 2020 Arne Brasseur and Contributors

Licensed under the term of the Mozilla Public License 2.0, see LICENSE.
