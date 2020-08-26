# Funnel

Transit-over-WebSocket Message Hub

<!-- badges -->
[![Github Actions](https://github.com/lambdaisland/funnel/workflows/native_image/badge.svg)](https://github.com/lambdaisland/funnel/actions)
[![Cljdoc Documentation](https://cljdoc.org/badge/lambdaisland/funnel)](https://cljdoc.org/d/lambdaisland/funnel)
[![Clojars Project](https://img.shields.io/clojars/v/lambdaisland/funnel.svg)](https://clojars.org/lambdaisland/funnel)
<!-- /badges -->

## What is it?

Funnel is a WebSocket message hub. It accepts connections from multiple clients,
and then acts as a go-between, funneling messages between them, with flexible
mechanisms for setting up message routing, either by the sender (broadcast) or
receiver (subscribe). It also provides discoverability, so clients can find out
who is there to talk to.

<!-- opencollective -->

&nbsp;

<img align="left" src="https://github.com/lambdaisland/open-source/raw/master/artwork/lighthouse_readme.png">

&nbsp;

## Support Lambda Island Open Source

funnel is part of a growing collection of quality Clojure libraries and
tools released on the Lambda Island label. If you are using this project
commercially then you are expected to pay it forward by
[becoming a backer on Open Collective](http://opencollective.com/lambda-island#section-contribute),
so that we may continue to enjoy a thriving Clojure ecosystem.

&nbsp;

&nbsp;

<!-- /opencollective -->

## What is it for?

Funnel grew out of the need to persist connections with JavaScript runtimes.
When tooling (a REPL, a test runner, a remote object browser) needs a connection
to a JavaScript runtime (say, a browser tab), then it has to wait for the
browser tab to connect back. There is no way to query for existing runtimes and
connect to them, we can only spawn a new one, and wait for it to call back.

Funnel provides persistence and discoverability. It keeps connections to long
lived processes (like a browser), so that short-lived processes (like a test
runner) can discover and interact with them.

In this way Funnel forms a bridge between developer tooling and JavaScript
runtimes. It keeps persistent connections to runtimes so individual tools don't
have to. This is particularly relevant when the tool's process lifetime is
shorter than the lifetime of the JavaScript runtime.

To make that concrete, a test runner invoked multiple times from the command
line can run commands in the same pre-existing browser tab.

Funnel accepts websocket connections on the endpoint `ws://localhost:44220`, and
optionally `wss://localhost:44221`. Any messages it receives are forwarded to
other clients based on active subscriptions (set up by the receiver), or based
on a broadcast command inside the message (added by the sender).

## Installation

You can download pre-compiled binaries for Linux and Mac OS from the
[releases](https://github.com/lambdaisland/funnel/releases) page, or run it with
this one-liner.

``` shell
clojure -Sdeps '{:deps {lambdaisland/funnel {:mvn/version "0.1.42"}}}' -m lambdaisland.funnel --help
```

## Usage

As an end user you are generally more interested in the tools that use Funnel
than in Funnel itself. In that the case the only thing that matters is that
Funnel is running. You can start it once and then forget about it.

```
~/funnel
```

### Verbosity

By default Funnel provides very little output, only errors and warnings are
displayed. You can increase the verbosity with `--verbose`/`-v` which can be
supplied up to three times

``` shell
./funnel -v
./funnel -vv
./funnel -vvv
```

`-v` will show opening and closing of connections but not individual messages.

`-vv` is a good middle ground for when you want to see what's being sent across,
without being inundated with implementation details

`-vvv` gets really noisy, including showing things like the websocket handshake
and raw transit.

When debugging issues Funnel provides a great place to inspect the flow of
messages going back and forth, which can provide useful information to
maintainers. When reporting bugs to Funnel-based tooling it's a good idea to
capture the traffic with

```
./funnel -vv --logfile funnel.log
```

And share the resulting `funnel.log` file as a [gist](https://gist.github.com/).

### WSS / SSL / HTTPS

By default Funnel only listens for regular, non encrypted websocket connections.
If you are running your development server with SSL enabled (HTTPS), then your
browser will refuse to connect to a non-encrypted websocket, and things will
quietly fail.

In this case you need to supply Funnel with a certificate in the form a `.jks`
file (Java Key Store), so that it can listen for WSS (webocket over ssl)
connections.

```
./funnel --keystore dev-cert.jks --keystore-password mypass123
```

If you already have a jks file that you are using for your dev setup then just
use that. Otherwise we recommend using [Certifiable](https://github.com/bhauman/certifiable)
to generate a certificate.

The default password for Certifiable and for Funnel is `"password"`, so if you
are using Certifiable you don't need to supply a password.

```
./funnel --keystore ~/_certifiable_certs/localhost-1d070e4/dev-server.jks
```

### Backgrounding

Funnel acts as a registry for connected clients, so that clients that join later
can query Funnel to discover who's available to talk to. This is why it's import
to run Funnel as a separate long-lived process, rather than for instance
embedding it into the tool that uses it.

To make this easy the native-image version allows backgrounding itself, so that
it detaches itself from the shell that started it, and will continue running in
the background.

```
./funnel --daemonize
588904
```

This prints the PID of the background process and exits. Use `kill -SIGINT
<pid>` to quit funnel.

You can even invoke this from a start-up script like `.xsessionrc` or
`.bash_profile` and forget about it. Running this multiple times is safe, if
Funnel finds that another instance is already listening on its port then it will
print a warning and exit with code 42.

When invoked as a daemon Funnel's log output will be directed to a logfile, this
defaults to `funnel.log` in the `java.io.tmpdir` (e.g. `/tmp/funnel.log`). Note
that the verbosity settings still apply, so by default you won't see much in the
logs unless errors occur. For more meaningful output supply one or more `-v`
options.

```
./funnel --daemonize -vv --logfile ~/funnel.log
```

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
but it can basically contain anything. Map keys SHOULD be keywords (qualified or
not), map values SHOULD be atomic/primitive (e.g. strings, keywords, numbers.
Not collections). Use of other types as keys or values is reserved for future
extension.

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
`:funnel/subscribe` is a _selector_. See the [Selector](#selector) section for
defaults.


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

## Selectors

`:funnel/subscribe`, `:funnel/unsubscribe`, and `:funnel/query` all take a
_selector_ as their associated value. A selector is an EDN value, this value is
matched against the stored `:funnel/whoami` maps to select a subset of clients.

Note that a message is never echoed back to the sending client, if if that
client would in principle be included in the selection.

### `true`

The boolean value `true` matches all connected clients (except the client the
message came from). This includes clients that have connected but have not
identified themselves by sending a whoami map. This is the only selector that
can select clients without stored whoami data.

### two-element vector

Interpreted as a key-value pair, will match all clients whose whoami map
contains exactly this key and associated value.

Note that while the current implementation simply compares values for equality,
we only officially support (and thus guarantee backwards compatibility) for
"atomic" values: strings, keywords, symbols, numbers, booleans. The behavior of
collections (maps, vectors, etc) as values in whoami maps, or in selectors, is
undefined, and may change in the future.

### Map

Will map all clients whose whoami maps contain identical key-value pairs as the
given map. Note that there may be extra information in the whoami map, this is
ignored. Same caveat as above: the behavior of collections as values is reserved
for future extension.

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

## Testing / debugging with jet and websocat

[jet](https://github.com/borkdude/jet) lets you convert easily between EDN and
Transit. [websocat](https://github.com/vi/websocat) provides a command line
interface for websockets. Together they form a great way for doing ad-hoc
communication with Funnel.

``` shell
echo '{:funnel/query true}' | jet --to transit | websocat ws://localhost:44220 | jet --from transit
```

## Prior Art

The design of Funnel is influenced by shadow-cljs's `shadow.remote`.

<!-- contributing -->
## Contributing

Everyone has a right to submit patches to funnel, and thus become a contributor.

Contributors MUST

- adhere to the [LambdaIsland Clojure Style Guide](https://nextjournal.com/lambdaisland/clojure-style-guide)
- write patches that solve a problem. Start by stating the problem, then supply a minimal solution. `*`
- agree to license their contributions as MPL 2.0.
- not break the contract with downstream consumers. `**`
- not break the tests.

Contributors SHOULD

- update the CHANGELOG and README.
- add tests for new functionality.

If you submit a pull request that adheres to these rules, then it will almost
certainly be merged immediately. However some things may require more
consideration. If you add new dependencies, or significantly increase the API
surface, then we need to decide if these changes are in line with the project's
goals. In this case you can start by [writing a pitch](https://nextjournal.com/lambdaisland/pitch-template),
and collecting feedback on it.

`*` This goes for features too, a feature needs to solve a problem. State the problem it solves, then supply a minimal solution.

`**` As long as this project has not seen a public release (i.e. is not on Clojars)
we may still consider making breaking changes, if there is consensus that the
changes are justified.
<!-- /contributing -->

<!-- license -->
## License

Copyright &copy; 2020 Arne Brasseur and Contributors

Licensed under the term of the Mozilla Public License 2.0, see LICENSE.
<!-- /license -->
