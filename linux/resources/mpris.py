#!/usr/bin/python3
"""Expose desktop playback through the Linux session bus."""
import json
import os
import sys
import time

from gi.repository import Gio, GLib

ROOT = "org.mpris.MediaPlayer2"
PLAYER = ROOT + ".Player"
OBJECT = "/org/mpris/MediaPlayer2"
EMPTY = OBJECT + "/TrackList/NoTrack"
XML = """
<node>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/><method name="Quit"/>
    <property name="CanQuit" type="b" access="read"/>
    <property name="CanRaise" type="b" access="read"/>
    <property name="HasTrackList" type="b" access="read"/>
    <property name="Identity" type="s" access="read"/>
    <property name="DesktopEntry" type="s" access="read"/>
    <property name="SupportedUriSchemes" type="as" access="read"/>
    <property name="SupportedMimeTypes" type="as" access="read"/>
  </interface>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Next"/><method name="Previous"/><method name="Pause"/>
    <method name="PlayPause"/><method name="Stop"/><method name="Play"/>
    <method name="Seek"><arg type="x" direction="in"/></method>
    <method name="SetPosition"><arg type="o" direction="in"/><arg type="x" direction="in"/></method>
    <method name="OpenUri"><arg type="s" direction="in"/></method>
    <signal name="Seeked"><arg type="x"/></signal>
    <property name="PlaybackStatus" type="s" access="read"/>
    <property name="Metadata" type="a{sv}" access="read"/>
    <property name="Rate" type="d" access="readwrite"/>
    <property name="Volume" type="d" access="readwrite"/>
    <property name="Position" type="x" access="read">
      <annotation name="org.freedesktop.DBus.Property.EmitsChangedSignal" value="false"/>
    </property>
    <property name="MinimumRate" type="d" access="read"/>
    <property name="MaximumRate" type="d" access="read"/>
    <property name="CanGoNext" type="b" access="read"/>
    <property name="CanGoPrevious" type="b" access="read"/>
    <property name="CanPlay" type="b" access="read"/>
    <property name="CanPause" type="b" access="read"/>
    <property name="CanSeek" type="b" access="read"/>
    <property name="CanControl" type="b" access="read"/>
  </interface>
</node>
"""
V = GLib.Variant
state = {}
updated = time.monotonic()
buffer = b""
loop = GLib.MainLoop()
bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)


def track_id():
    return "/dev/rewindamp/Track/" + state["key"].replace("-", "_") if state.get("key") else EMPTY


def position():
    position_us = state.get("position", 0)
    if state.get("playing"):
        position_us += int((time.monotonic() - updated) * state.get("rate", 1.0) * 1_000_000)
    return min(state["duration"], position_us) if state.get("duration", 0) > 0 else position_us


def properties():
    active = bool(state.get("key"))
    metadata = {"mpris:trackid": V("o", track_id())}
    if active:
        metadata.update({"xesam:title": V("s", state["title"].replace("\0", "")),
                         "xesam:artist": V("as", [state["artist"].replace("\0", "")]),
                         "xesam:album": V("s", state["album"].replace("\0", ""))})
        if state["duration"] > 0:
            metadata["mpris:length"] = V("x", state["duration"])
    return {
        "PlaybackStatus": V("s", "Playing" if state.get("playing") else "Paused" if active else "Stopped"),
        "Metadata": V("a{sv}", metadata), "Position": V("x", position()),
        "Rate": V("d", float(state.get("rate", 1.0))), "Volume": V("d", float(state.get("volume", 1.0))),
        "CanPlay": V("b", active), "CanPause": V("b", active),
        "CanSeek": V("b", active and state.get("duration", 0) > 0), "CanControl": V("b", True),
        "CanGoNext": V("b", state.get("next", False)), "CanGoPrevious": V("b", state.get("previous", False)),
        "MinimumRate": V("d", 0.75 if state.get("podcast") else 1.0),
        "MaximumRate": V("d", 3.0 if state.get("podcast") else 1.0),
    }


def get_prop(_bus, _sender, _path, interface, prop):
    if interface == PLAYER:
        return properties().get(prop)
    return {
        "Identity": V("s", "Rewindamp"), "DesktopEntry": V("s", "dev.rewindamp"),
        "CanQuit": V("b", True), "CanRaise": V("b", True), "HasTrackList": V("b", False),
        "SupportedUriSchemes": V("as", []), "SupportedMimeTypes": V("as", []),
    }.get(prop)


def dispatch(_bus, _sender, _path, _interface, method, parameters, invocation):
    if method == "OpenUri":
        invocation.return_dbus_error("org.freedesktop.DBus.Error.NotSupported", "Choose audio in Rewindamp")
        return
    args = parameters.unpack()
    if method == "SetPosition" and (args[0] != track_id() or not 0 <= args[1] <= state.get("duration", 0)):
        invocation.return_value(None)
        return
    if method in {"Seek", "SetPosition"} and state.get("duration", 0) <= 0:
        invocation.return_value(None)
        return
    if method == "Seek" and position() + args[0] >= state.get("duration", 0):
        method, args = ("Next", ()) if state.get("next") else ("SetPosition", (track_id(), state["duration"]))
    print(json.dumps({"action": method, "position": args[-1] if args else 0}), flush=True)
    invocation.return_value(None)


def set_prop(_bus, _sender, _path, interface, prop, value):
    if interface != PLAYER or prop not in {"Volume", "Rate"}:
        return False
    number = value.unpack()
    if not float("-inf") < number < float("inf"):
        return False
    if prop == "Rate" and number != 0 and not (0.75 <= number <= 3 if state.get("podcast") else number == 1):
        return False
    number = min(1, max(0, number)) if prop == "Volume" else number
    print(json.dumps({"action": prop, "position": round(number * 1000)}), flush=True)
    return True


def receive(fd, _events):
    global buffer, state, updated
    chunk = os.read(fd, 8192)
    if not chunk:
        loop.quit()
        return False
    buffer += chunk
    while b"\n" in buffer:
        line, buffer = buffer.split(b"\n", 1)
        old = properties()
        old_key = state.get("key")
        try:
            state = json.loads(line)
        except ValueError:
            continue
        updated = time.monotonic()
        new = properties()
        changed = {key: value for key, value in new.items() if key != "Position" and value != old[key]}
        if changed:
            bus.emit_signal(None, OBJECT, "org.freedesktop.DBus.Properties", "PropertiesChanged", V("(sa{sv}as)", (PLAYER, changed, [])))
        if old_key == state.get("key") and abs(new["Position"].unpack() - old["Position"].unpack()) > 1_700_000:
            bus.emit_signal(None, OBJECT, PLAYER, "Seeked", V("(x)", (new["Position"].unpack(),)))
    return True


for interface in Gio.DBusNodeInfo.new_for_xml(XML).interfaces:
    bus.register_object(OBJECT, interface, dispatch, get_prop, set_prop)
owner = Gio.bus_own_name_on_connection(bus, ROOT + ".Rewindamp", Gio.BusNameOwnerFlags.NONE, None, None)
GLib.io_add_watch(sys.stdin.fileno(), GLib.PRIORITY_DEFAULT, GLib.IO_IN | GLib.IO_HUP, receive)
loop.run()
Gio.bus_unown_name(owner)
