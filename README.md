![OpenPNP Logo](https://raw.githubusercontent.com/openpnp/openpnp-logo/develop/logo_small.png)

# OpenPnP

Open Source SMT Pick and Place Hardware and Software

## Introduction

OpenPnP is a project to create the plans, prototype and software for a completely Open Source SMT
pick and place machine that anyone can afford. I believe that with the ubiquity of cheap, precise
motion control hardware, some ingenuity and plenty of Open Source software it should be possible
to build and own a fully functional SMT pick and place machine for under $1000.

## Project Status

OpenPnP is stable and in wide use. It is still under heavy development and new features are added continuously. See the [Downloads](http://openpnp.org/downloads) page to get started.

If you would like to keep up with our progress you can
[Watch this project on GitHub](http://github.com/openpnp/openpnp), check out
[our Twitter](http://twitter.com/openpnp), [join the discussion group](http://groups.google.com/group/openpnp),
or come chat with us on [Discord](https://discord.gg/EmsrFVx).

## Contributing

![Build Status](https://github.com/openpnp/openpnp/workflows/Build%20and%20Deploy%20OpenPnP/badge.svg)
[![Help Wanted](https://img.shields.io/github/issues-raw/openpnp/openpnp/help-wanted.svg?label=help-wanted&colorB=5319e7)](https://github.com/openpnp/openpnp/labels/help-wanted)
[![Bugs](https://img.shields.io/github/issues-raw/openpnp/openpnp/bug.svg?label=bugs&colorB=D9472F)](https://github.com/openpnp/openpnp/labels/bug)
[![Feature Requests](https://img.shields.io/github/issues-raw/openpnp/openpnp/feature-request.svg?label=feature-requests&colorB=bfd4f2)](https://github.com/openpnp/openpnp/labels/feature-request)
[![Enhancements](https://img.shields.io/github/issues-raw/openpnp/openpnp/enhancement.svg?label=enhancements&colorB=0052cc)](https://github.com/openpnp/openpnp/labels/enhancement)


Before starting work on a pull request, please read: https://github.com/openpnp/openpnp/wiki/Developers-Guide#contributing

Summary of guidelines:

* One pull request per issue.
* Describe the change.
* Follow the coding style.
* Include tests and documentation.
* Think of the big picture.

## Photon Feeder Performance Features

This fork includes several throughput improvements for [Photon feeders](https://github.com/photonfirmware/photon):

| Feature | How it works | Est. CPH gain |
|---|---|---|
| **Move While Feeding** | Nozzle travels to pick location while the tape is still advancing | 5–10 % |
| **Feed After Pick** | Fires the next advance command immediately after pick; nozzle moves to alignment/place while the tape advances in the background | 5–15 % typical, up to 25–30 % for large-pitch parts |
| **Per-feeder Pocket Calibration** | Top-camera vision corrects the pick offset per feeder, reducing pick failures | Fewer retries |

Both feed-timing options are enabled by default and can be toggled per-feeder under the **Location** tab in the feeder configuration panel.

## Thanks

Many thanks to ej-technologies for providing a complimentary license of install4j. install4j
creates high quality, professional installers for Java applications.

More information at http://www.ej-technologies.com/products/install4j/overview.html.
