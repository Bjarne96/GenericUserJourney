## Description
This repository contains the source code for my bachelor's thesis "Analyzing Persistent Patterns from the Web to construct Datasets based on User Journeys", including the functionality of a web scraper and the ability to import data from a traffic dataset generated by AWS Athena.
## Abstract
This work focuses on the generation of an artificial user journey designed to replicate web
traffic on web domains, where only publicly available data is given. The artificial user
journey is created by a web scraper that follows a framework based on user interface
design principles. It is intended for the application in other research that relies on
web traffic analysis, but lacks access to the tracking data. The focus of this bachelor
thesis centers on webshops, as opposed to websites, given the limited scope. The work
involves the creation of a framework based on design principles to imitate user behavior.
This framework is implemented into a web scraper and compared against authentic
tracking data sourced from a webshop. The validation methods are based on the page
type, a substantial representation of the data transmitted when loading a web page.
The main challenges encountered throughout this undertaking stem from the inherent
inconsistencies spread across diverse webshops, particularly in terms of HTML structures
and journeys with multiple distinct purposes. This necessitats the exploration of a
diverse range of solutions to identify page types and determine the optimal route for the
artificial user journey. By the end of this work, it becomes evident that a framework
based on design principles consists of significant potential. There is still considerable
additional work required to attain consistent results.

## Tech-Stack
- Kotlin: Chosen as a stable solution for the Selenium Driver and due to its ability to handle time-intensive and performance-heavy data imports.
- Selenium: Utilized as a web scraper for rendering websites and executing a wide range of functionalities.
- SQLite: Used for local data storage due to its easy-to-learn nature and scalable capabilities.

## Learnings
- The understanding of design principles and patterns observered in major German e-commerce websites.
- Employed techniques to mitigate web scraping countermeasures used by major German e-commerce websites.
- Acquired in-depth knowledge of Selenium's capabilities.
- Improved programming skills in Java/Kotlin.


