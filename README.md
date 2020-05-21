# Funnel

Transit-over-WebSocket Message Relay

<!-- badges -->
[![CircleCI](https://circleci.com/gh/lambdaisland/funnel.svg?style=svg)](https://circleci.com/gh/lambdaisland/funnel) [![cljdoc badge](https://cljdoc.org/badge/lambdaisland/funnel)](https://cljdoc.org/d/lambdaisland/funnel) [![Clojars Project](https://img.shields.io/clojars/v/lambdaisland/funnel.svg)](https://clojars.org/lambdaisland/funnel)
<!-- /badges -->

## What is it?

Funnel is a WebSocket relay. It accepts connections from multiple clients, and
then acts as a go-between, funneling messages between them. Messages can be
addressed at a specific client, or broadcasted to a selection of clients.

## What is it for?

Funnel grew out of the need to persist connections with JavaScript runtimes.
When tooling (a REPL, a test runner, a remote object browser) needs a connection
to a JavaScript runtime (say, a browser tab), then it has to wait for the
browser tab to connect back. There is no way to query for existing runtimes and
connect to them, we can only spawn a new one, and wait for it to call back.

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

## Messages

Each message Funnel receives on a websocket is decoded with Transit. If the
decoded message is a map then Funnel will look for the presence of certain keys,
which will trigger specific processing, before being forward to other connected
clients based on active subscriptions, or the presence of a `:funnel/broadcast`
key.

### `:funnel/whoami`

When a message contains a `:funnel/whoami` key, then the value of that key MUST
be a map with identifying information.

The `:funnel/whoami` map SHOULD contain an `:id`, `:type`, and `:description`,
but it can basically contain anything. The map keys and values MUST have value
semantics (be comparable with `=`).

``` clojure
{:funnel/whoami {:id "firefox-123"
                 :type :kaocha.cljs2/js-runtime
                 :description "Firefox 78.0 on Linux"}}
```

The contents of this map are stored as a property of the client connection. They
are used for selecting clients when routing messages, and can be returned when
querying for connected clients. The client SHOULD include a whoami map in the
first message they send. It can be omitted from subsequent message, since the
stored map will be used.

If funnel receives a new `:funnel/whoami` then it will replace the old one.

### `:funnel/subscribe`

A client who wishes to receive messages sent by a subset of connected clients
can send a message containing a `:funnel/subscribe`. The value of
`:funnel/subscribe` is a _selector_.

Currently a selector can be either a two-element vector, corresponding with a
key/value pair found in the whoami-map, or `true`, which matches all clients.

``` clojure
{:funnel/whoami {:id "test-suite-abc-123"
                 :type :kaocha.cljs2/run}
 :funnel/subscribe [:type :kaocha.cljs2/js-runtime]}
```

This will create a persistent subscription, all incoming messages matching the
selector will be forwarded to the client that issued the subscription. A client
can create multiple subscriptions.

Note that the current sender is always excluded, so a message is never sent back
to the sender, even if a subscription or broadcast selector matches the
`:funnel/whoami` of the sender.

### `:funnel/unsubscribe`

To remove a subscription, use the `:funnel/unsubscribe` key with the same
selector used in `:funnel/subscribe`.

### `:funnel/broadcast`

Clients can send arbitrary messages to funnel without caring where they go. If
there is a matching subscription then they will get forwarded, if not they are
dropped. But a client may also choose to address a message to a specific client
or subset of clients, by using `:funnel/broadcast`. The value of
`:funnel/broadcast` is again a _selector_, as with `:funnel/subscribe`.

``` clojure
{:type :kaocha.cljs2/fetch-test-data
 :funnel/broadcast [:type :kaocha.cljs2/js-runtime]}
```

When a message is received a set of recipients is determined based on any
existing subscriptions, and possibly the presence of a `:funnel/broadcast` value
inside the message. These are unified, so a given message is sent to a given
client at most once.

### `:funnel/query`

When a received message contains the `:funnel/query` key, then funnel will send
a message back to the client containing a `:funnel/clients` list, which is a
sequence of whoami-maps, based on the selector.

``` clojure
;; =>
{:funnel/query true}

;; <=
{:funnel/clients
 [{:id "firefox-123"
   ,,,}
  ,,,]}
```

## Message forwarding

When a message is received we try to decode it as transit. If the decoded value
is a map then we look for the above keys and handle `:funnel/whoami`,
`:funnel/subscribe`, `:funnel/unsubscribe`, and `:funnel/query`.

Then we determine the recipients of the message, based on existing subscriptions,
and if present on the value of `:funnel/broadcast`.

Note that messages don't have to be maps, or even valid transit. In that case
they are still forwarded based on active subscriptions.

If a value does decode to a map, and it does not contain a `:funnel/whoami`
value, then the last seen value of `:funnel/whoami` is added.

Tagged values are forwarded as-is, there is no need to configure read or write
handlers inside Funnel.

Note that apart from the above keys clients can add any arbitrary values to
their messages, and Funnel will funnel them.

## Disconnect handling

When a client disconnects all matching subscribers are notified with a message
of the form

``` clojure
{:funnel/disconnect {:code ... :reason ... :remote? ...}
 :funnel/whoami {...}}
```

Subscribers are *not* notified of new connections per-se, instead when a client
announces itself with a `:funnel/whoami` then that first message will be
forwarded to matching subscribers (like any other message).

## Prior Art

The design of Funnel is influenced by shadow-cljs's `shadow.remote`.

## License

Copyright &copy; 2020 Arne Brasseur and Contributors

Licensed under the term of the Mozilla Public License 2.0, see LICENSE.
