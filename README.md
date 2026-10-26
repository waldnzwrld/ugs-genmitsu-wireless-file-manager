# File management for the Genmitsu wireless controller

<img width="2062" height="1788" alt="screenshot-20261026-180049" src="https://github.com/user-attachments/assets/68c71aa5-f535-4fc9-9869-56f6d625a28b" />


The Genmitsu wifi controller is an excellent addition to your CNC machine,
and the iOS and Android apps are great, but the interface for the phone apps leaves a lot to be desired. 

UGS is a great cnc controller, but if I am using it over wifi and the signal is interrupted for any reason 
it can cause major problems for the cnc. 

I created this plugin to take the best functionality from the genmitsu phone apps (the ability to store gcode locally,
and execute a program from the machine) and add it to the best cnc controller (Universal Gcode Sender)

## Installation

### Use the prebuilt plugin

Download the `.nbm` file from the
[latest release](https://github.com/waldnzwrld/ugs-genmitsu-wireless-file-manager/releases/latest).

1. In UGS Platform, open **Tools → Plugins → Downloaded → Add Plugins…**
2. Select the downloaded `.nbm` file, click **Install**, accept the prompts, and restart UGS if asked.
3. Open the panel from **Window → Plugins → Genmitsu SD Card**.

### Build it yourself

The plugin builds against the UGS platform artifacts (internal version `2.0-SNAPSHOT`), which are not
published to Maven Central. Build and install UGS from source first so they land in `~/.m2`:

```sh
git clone https://github.com/winder/Universal-G-Code-Sender.git
cd Universal-G-Code-Sender
mvn install -DskipTests
```

Then, from this repo:

```sh
mvn package
```

The plugin is written to `target/ugs-platform-plugin-genmitsu-sd-2.0-SNAPSHOT.nbm`; install it as above.
Requires a JDK new enough for the UGS version you build against (the code uses Java records, so 17+).

### Connect

1. Join the same network as the Genmitsu Wi-Fi module and find its IP address.
2. In UGS, pick the **TCP** connection driver and connect to `<module-ip>:10086`.
3. Open the **Genmitsu SD Card** window. It reads the card as soon as it opens; use **Refresh** after reconnecting.

## How it works

The Wi-Fi module speaks two things on TCP port 10086: plain GRBL (which UGS already understands) and a
small JSON protocol that the phone apps use to manage the SD card. The JSON commands were recovered from
the vendor app:

| Command | Purpose |
|---------|---------|
| `ar` | list files on the card |
| `ft` … `fe` | upload a file (file record, then the raw bytes, then end) |
| `cf` | delete a file |
| `be` | begin running a file from the card |
| `rp` / `rc` / `ed` | pause / resume / end (stop) the running job |
| `po` | job progress (only answered while a job is running) |

The module only accepts **one client at a time**, so the plugin doesn't open its own connection. It
reuses the TCP connection UGS already has:

- JSON commands are written straight to UGS's connection, bypassing the command queue (they never
  get a GRBL `ok`, so the queue would wait forever).
- Replies come back as one JSON object per line. The plugin listens to UGS's raw responses and picks
  those out; UGS's GRBL handler ignores them.
- UGS's `?` status polling is paused for each exchange so a status query can't land in the middle
  of an upload's file bytes, and resumed afterwards.

For uploads, the plugin builds the same file record the app sends (size, line count, bounds, feed
rates, estimated cutting time) and streams the file in chunks. The progress bar follows the bytes
sent, then the bytes the module reports as written to the card.

Once you hit **Start**, the job runs **from the SD card on the controller**. The G-code isn't
streamed over Wi-Fi, so a dropped connection won't stall or corrupt the cut. That's the reason
this plugin exists.

### Using the panel

- **Refresh**: re-read the card.
- **Upload…**: send one or more G-code files to the card (defaults to the file open in UGS, and
  asks before overwriting).
- **Load**: choose the selected file as the one **Start** will run. Like the app's confusingly named
  "Upload" button, this sends nothing to the machine.
- **Delete**: remove the selected file from the card.
- **Start / Pause / Resume / Stop**: control the job on the controller. Progress shows next to the
  loaded file while a job is running.

## Things to watch out for

- **Close the phone app.** The module only allows one client. If the app is connected, UGS can't
  connect, and the other way round.
- **Use the TCP driver.** The plugin refuses to work over any other UGS connection type.
- **UGS has to be idle.** Card operations are refused while UGS is streaming a file or has commands
  in flight. Don't start a UGS send while a card job is running either: the controller is already
  busy with the job.
- **Stop means the module's stop.** Pause/Resume/Stop in this panel go to the module, not to UGS's
  feed hold or soft reset. Keep the physical e-stop within reach, as always.
- **Text files only.** Files have to be valid UTF-8. Files with non-ASCII characters (for example
  in comments) also need UGS running with a UTF-8 default charset (`-Dfile.encoding=UTF-8`);
  otherwise strip those characters or the upload is refused.
- **The time estimate is rough.** Rapids are assumed to run at 1000 mm/min and acceleration is
  ignored. The module only uses it for display.
- **Progress only appears while a job runs.** The module doesn't answer progress requests when idle,
  so the progress text clears once the job ends or the connection drops.
- **Tied to UGS internals.** UGS doesn't expose its connection publicly, so the plugin reads it by
  reflection. A future UGS release could break this; you'll get an error saying the connection
  isn't exposed as expected.
