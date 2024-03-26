import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import org.intellij.lang.annotations.Language
import org.openqa.selenium.*
import org.openqa.selenium.firefox.FirefoxDriver
import java.awt.geom.Point2D
import java.net.URLDecoder
import java.nio.file.Paths
import java.security.MessageDigest
import java.sql.*
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/* *** Data classes *** */
private data class VisitData(
    val domain: String,
    val pagePerVisit: Int,
    val bounceRate: Double,
    val totalVisits: Long
)

private data class Link (
    var currentUrl: String,
    val nextHref: String,
    var pageType: String,
    val sessionIndex: Int,
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    var locationScore: Double,
    var sizeScore: Double,
    var title: String
)

private data class LinkData(
    var x: Int,
    var y: Int,
    val href: String,
    var width: Int,
    var height: Int,
    var locationScore: Double,
    var sizeScore: Double,
)

private data class LinkSize(
    var width: Int,
    var height: Int,
)

private data class SessionEntry(
    val sessionEntryId: String,
    val sessionId: String,
    val sessionIndex: Int,
    val url: String,
    val pageType: String,
)

private data class Session(
    val sessionId: String,
    val device: String,
    var sessionCount: Int?,
)

/* *** Filesystem variables *** */
private val systemPath: String = System.getProperty("user.dir")
private val cookieRemoveExtensionPath = "${systemPath}\\src\\main\\drivers\\istilldontcareaboutcookies-1.1.1.xpi"

/* *** Helper functions *** */
private fun roundToTwoDecimalPlaces(number: Double): Double {
    return (number * 100.0).roundToInt() / 100.0
}
private fun generateRandomHashOfSixCharacters(): String {
    val bytes = ByteArray(6)
    Random.nextBytes(bytes)
    val messageDig = MessageDigest.getInstance("SHA-256")
    val hashBytes = messageDig.digest(bytes)
    return hashBytes.joinToString("") { "%02x".format(it) }.substring(0, 6)
}
private fun getVisits(): ArrayList<VisitData> {
    val visits = ArrayList<VisitData>()
    // Dataset from https://www.similarweb.com/de/top-websites/germany/e-commerce-and-shopping/, September 2023, All Devices
    visits.add(VisitData("https://www.amazon.de/", 9, 0.3156, 412400000)) // 412.400.000
    visits.add(VisitData("https://www.ebay.de/", 8, 0.3077, 146700000)) // 146.700.000
    visits.add(VisitData("https://www.kleinanzeigen.de/", 10, 0.2908, 137500000)) //137.500.000
    visits.add(VisitData("https://www.otto.de/", 7, 0.4317, 53200000)) //53.200.000
    visits.add(VisitData("https://www.idealo.de/", 4, 0.4412, 45500000)) // 45.500.000
    return visits
}

/* *** Formatting CSV to Database functions *** */
private fun formatRawToSessionClass(array: List<String>): Session {
    if (array.size != 6) return Session("", "", Int.MAX_VALUE)
    var device = ""
    try {
        device = array[2].trim()
    } catch(e: Exception) {
        println(e)
    }
    if(device != "Desktop") return Session("", "", Int.MAX_VALUE)
    return Session(array[1].trim(), device, null)
}
private fun formatRawToEntryClass(array: List<String>): SessionEntry {
    if (array.size != 6) return SessionEntry("", "", Int.MAX_VALUE, "", "")
    var index = Int.MAX_VALUE
    var url = array[4].replace(" ", "")
    var device = ""
    try {
        device = array[2].trim()
    } catch(e: Exception) {
        println(e)
    }
    if(device != "Desktop")  return SessionEntry("", "", Int.MAX_VALUE, "", "")
    url = try {
        URLDecoder.decode(url, "UTF-8")
    } catch(e: Exception) {
        println(e)
        println(url)
        ""
    }

    try {
        val temp = array[3].trim()
        index = temp.toInt()
    } catch(e: Exception) {println(e) }
    if(url == "") return SessionEntry("", "", Int.MAX_VALUE, "", "")
    return SessionEntry(array[0].trim(), array[1].trim(), index, url, array[5].trim())
}
private fun formatRawToSession(rawFilePath: String,dbPath: String) {
    println("dbPath $dbPath")
    println("rawFilePath $rawFilePath")
    var connection: Connection = DriverManager.getConnection(dbPath)
    val statement: Statement = connection.createStatement()
    try {
        @Language("SQL")
        var createTable =  """
                CREATE TABLE IF NOT EXISTS SESSION_ (
                    SESSION_ID TEXT PRIMARY KEY,
                    DEVICE TEXT,
                    SESSION_COUNT INT
                );
            """.trimIndent()
        statement.execute(createTable)
        statement.close()
        @Language("SQL")
        createTable ="""
                CREATE TABLE IF NOT EXISTS SESSION_ENTRY (
                    SESSION_ENTRY_ID TEXT PRIMARY KEY,
                    SESSION_ID TEXT,
                    SESSION_INDEX INT,
                    URL TEXT,
                    PAGETYPE TEXT,
                    FOREIGN KEY (SESSION_ID) REFERENCES SESSION_(SESSION_ID)
                );
            """.trimIndent()
        statement.execute(createTable)
        statement.close()
        println("created tables")
        var counter = 0
//        var limiter = 0
        var insertCounter = 0
        var sessionCounter = 0
        // Gather Insert SessionEntries
        val sessionEntryInserts = ArrayList<SessionEntry>()
        // Insert when session qas gathered
        var lastSession: Session? = null

        val rowNumbers = 4243828
        // Create DB from Raw CSV File
        println("read sessions from csv")
        csvReader().open(rawFilePath) {
            readAllAsSequence().forEachIndexed { j: Int, row ->
                if(j == 0) return@forEachIndexed
//                if(limiter != 0 && counter > limiter) return@forEachIndexed
                counter++
                if(counter % 50000 == 0) {
                    connection.close()
                    connection = DriverManager.getConnection(dbPath)
                    println("${roundToTwoDecimalPlaces((j.toDouble())/rowNumbers.toDouble())*100.toDouble()}% done.")
                    println("Total of inserted sessions: $insertCounter")
                }
                val row2: String = row.toString()
                val values = row2.substring(1, row2.length - 1)
                val results = values.split(",").map { it.trim('"') }
                val sessionEntry = formatRawToEntryClass(results)
                println(sessionEntry)
                val session = formatRawToSessionClass(results)
                println(session)
                if(sessionEntry.sessionIndex == Int.MAX_VALUE || session.device == "") return@forEachIndexed
                try {
                    // Insert data
                    if(lastSession == null) lastSession = session
                    if(lastSession!!.sessionId != session.sessionId) {
                        sessionCounter++
                        // Update Count
                        lastSession!!.sessionCount = sessionEntryInserts.size

                        if(lastSession!!.sessionCount != null && (lastSession!!.sessionCount!! == 4 || lastSession!!.sessionCount!! in 7..10)) {
                            val sortedSessionEntries = sessionEntryInserts.sortedBy { it.sessionIndex }
                            var ignoreInConsistentSession = false
                            var index = 0
                            for (entry in sortedSessionEntries) {
                                if(entry.sessionIndex != index) {
                                    ignoreInConsistentSession = true
                                    break
                                }
                                index++
                            }
                            if(!ignoreInConsistentSession) {
                                insertCounter++
                                // Insert Session to DB
                                insertSession(lastSession!!, connection)
                                // Insert Session Entries to DB
                                for(sessionEntryDb in sortedSessionEntries) insertSessionEntry(sessionEntryDb, connection)
                            }
                        }
                        // Clear for new Session gathering
                        lastSession = session
                        sessionEntryInserts.clear()
                    }
                    // Insert Session Entry
                    sessionEntryInserts.add(sessionEntry)
                } catch(e: Exception) {
                    println("error $e")
                    println(sessionEntry)
                    println(session)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        statement.close()
        connection.close()
    }
}

/* *** Database functions *** */
private fun insertSessionEntry(sessionEntry: SessionEntry, connection: Connection) {
    val sql = "INSERT INTO SESSION_ENTRY (SESSION_ENTRY_ID, SESSION_ID, SESSION_INDEX, URL, PAGETYPE) VALUES (?, ?, ?, ?, ?)"
    val preparedStatement: PreparedStatement = connection.prepareStatement(sql)
    try {
        preparedStatement.setString(1, sessionEntry.sessionEntryId)
        preparedStatement.setString(2, sessionEntry.sessionId)
        preparedStatement.setInt(3, sessionEntry.sessionIndex)
        preparedStatement.setString(4, sessionEntry.url)
        preparedStatement.setString(5, sessionEntry.pageType)
        preparedStatement.executeUpdate()
        preparedStatement.close()
    } catch(e: Exception) {
        preparedStatement.close()
        e.printStackTrace()
    }
}
private fun insertSession(session: Session, connection: Connection) {
    val sql = "INSERT INTO SESSION_ (SESSION_ID, DEVICE, SESSION_COUNT) VALUES (?, ?, ?)"
    val preparedStatement: PreparedStatement = connection.prepareStatement(sql)
    try {
        preparedStatement.setString(1, session.sessionId)
        preparedStatement.setString(2, session.device)
        session.sessionCount?.let { preparedStatement.setInt(3, it) }
        preparedStatement.executeUpdate()
        preparedStatement.close()
    } catch(e: Exception) {
        preparedStatement.close()
        println("sessionId: ${session.sessionId}")
        e.printStackTrace()
    }
}
private fun insertLinkEntry(link: Link, domain: String, connection: Connection) {
    val columns = "(ID, SESSION_NAME, SESSION_INDEX, CURRENT_URL,NEXT_HREF, WIDTH, HEIGHT, LOC_Y, LOC_X, SCORE, PAGE_TYPE)"
    val values = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    val sql = "INSERT INTO LINKS $columns VALUES $values"
    val preparedStatement: PreparedStatement = connection.prepareStatement(sql)
    val validatedDomain = extractDomainFromUrl(domain)
    try {
        preparedStatement.setString(1, "${UUID.randomUUID()}")
        preparedStatement.setString(2, validatedDomain)
        preparedStatement.setInt(3, link.sessionIndex)
        preparedStatement.setString(4, link.currentUrl)
        preparedStatement.setString(5, link.nextHref)
        preparedStatement.setInt(6, link.width)
        preparedStatement.setInt(7, link.height)
        preparedStatement.setInt(8, link.y)
        preparedStatement.setInt(9, link.x)
        preparedStatement.setDouble(10, (link.locationScore + link.sizeScore) / 2)
        preparedStatement.setString(11, link.pageType)
        preparedStatement.executeUpdate()
        preparedStatement.close()
    } catch(e: Exception) {
        preparedStatement.close()
        e.printStackTrace()
    }
}

/* *** Web Scraper functions *** */
private fun generateGenericUserJourneys(dbPath: String) {
//        Refactored
    /* *** Setting up the Firefox driver, with working cookie banner remover extension. **** */
    val visits = getVisits()
    val driver = FirefoxDriver()
    val driverPath = Paths.get(cookieRemoveExtensionPath)
    driver.installExtension(driverPath)
    driver.manage().window().maximize()
    Thread.sleep(2000)
    /* *** Setting up database tables and connection **** */
    var connection: Connection = DriverManager.getConnection(dbPath)
    try {
        val statement: Statement = connection.createStatement()
        @Language("SQL")
        val createTable =  """
            CREATE TABLE IF NOT EXISTS LINKS (
                ID TEXT ,
                SESSION_NAME TEXT,
                SESSION_INDEX INT ,
                CURRENT_URL TEXT,
                NEXT_HREF TEXT,
                WIDTH INT,
                HEIGHT INT,
                LOC_Y INT,
                LOC_X INT,
                SCORE DOUBLE,
                PAGE_TYPE TEXT,
                PRIMARY KEY ('ID')
            );
        """.trimIndent()
        statement.execute(createTable)
        statement.close()
        var breakJourney = false
        /* *** Loop through all domains, for which a generic user journey is supposed to be created **** */
        for (visit in visits) {
            if (breakJourney) {
                println("********************************")
                println("The journey for ${visit.domain} was broken, because no link could be gathered or no final link was found.")
                println("********************************")
                breakJourney = false
                continue
            }
            val genericUserSessionList = ArrayList<Link>()
            val blackList = ArrayList<Link>()
            var sessionIndex = 0
            var pageType = ""
            while (sessionIndex < visit.pagePerVisit) {
                println("sessionIndex $sessionIndex")
                var url = visit.domain
                if (genericUserSessionList.size != 0) {
                    url = genericUserSessionList[(sessionIndex - 1)].nextHref
                }
                println("url $url")
                driver.get(url)
                Thread.sleep(1000)
                pageType = evaluatePageType(url, driver)
                if(driver.currentUrl != url) {
                    driver.get(url)
                    Thread.sleep(1000)
                }
                val allLinks = getAllLinkData(visit.domain, driver)
                val sortedLinks = allLinks.sortedWith(compareByDescending { ((it.sizeScore + it.locationScore) / 2) })
                if (sortedLinks.isNotEmpty()) {
                    val finalLink = getFinalLink(sortedLinks, genericUserSessionList, blackList, visit.domain, sessionIndex, visit.pagePerVisit, driver)
                    if(finalLink == null) {
                        /* ***No final link could found on the page. **** */
                        breakJourney = true
                        break
                    } else {
                        val finalScore = (finalLink.locationScore + finalLink.sizeScore) /2
                        /* *** Test for a journey, the scraper can handle **** */
                        if(finalScore <= 5.0) {
                            blackList.add(finalLink)
                            var i = 0
                            for (link in genericUserSessionList) {
                                if(i == 0) {
                                    i++
                                    continue
                                }
                                println("blackListed ${link.currentUrl}")
                                blackList.add(link)
                                i++
                            }
                            sessionIndex = 0
                            genericUserSessionList.clear()
                            println("total reset session")
                        } else {
                            /* *** Successfully add the session entry to the generic user journey **** */
                            println("added session entry")
                            sessionIndex++
                            val nextPageType = finalLink.pageType
                            finalLink.pageType = pageType
                            pageType = nextPageType
                            genericUserSessionList.add(finalLink)
                        }
                    }
                } else {
                    /* ***No valid links could be gathered on the page. **** */
                    breakJourney = true
                    break
                }
            }
            /* *** After a generic user session was successfully created, it is saved in the database + database connection reset **** */
            for (finalLink in genericUserSessionList) {
                try {
                    connection = DriverManager.getConnection(dbPath)
                    insertLinkEntry(finalLink, visit.domain, connection)
                    connection.close()
                    connection = DriverManager.getConnection(dbPath)
                } catch (e: Exception) {
                    println(e)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        connection.close()
        driver.quit()
    }
}
private fun filterLinkListBySizeVariance(linkList: List<LinkData>, location: String): List<LinkData> {
    /* *** Filter elements with a maximum variance of 20 pixels in height and width *** */
    var listMatch = 0
    return linkList.filterIndexed { index, element ->
        if (index < linkList.size - 2) {
            val nextElement = linkList[index + 1]
            val nextElement2 = linkList[index + 2]
            val heightVariance = abs(element.height - nextElement.height)
            val widthVariance = abs(element.width - nextElement.width)
            var locationMatch = (element.x == nextElement.x) && (element.x == nextElement2.x)
            if(location == "y") locationMatch = (element.y== nextElement.y) && (element.y== nextElement2.y)
            var match = locationMatch && (heightVariance <= 20) && (widthVariance <= 20)
            if(match) listMatch++
            else {
                if(listMatch != 0) match = true
                listMatch = 0
            }
            match
        } else { (listMatch != 0) }
    }
}
private fun testProductDetailPageType(driver: WebDriver, potentialProductDetailUrl: String): Boolean {
    var isProductDetail = false
    if(driver.currentUrl != potentialProductDetailUrl) {
        driver.get(potentialProductDetailUrl)
        Thread.sleep(2000)
    }
    try {
        // Find all buttons and anchor tags on the page
        val allElements: List<WebElement> = driver.findElements(By.cssSelector("button, a, span"))
        // Search for text content within each element or its child elements
        val searchTexts = arrayOf("Artikel merken", "Zur Merkliste hinzufügen", "Zur Watchlist hinzufügen", "In den Einkaufswagen", "In den Warenkorb", "Auf die Beobachtungsliste").toCollection(ArrayList())
        val elementsWithText = ArrayList<WebElement>()
        for(element in allElements) {
            if(hasTextContent(element, searchTexts)) {
                elementsWithText.add(element)
                break
            }
        }
        if(elementsWithText.isNotEmpty()) isProductDetail = true
    } catch(e: Exception) {
        e.printStackTrace()
    }
    return isProductDetail
}
private fun hasTextContent(element: WebElement, searchTexts: ArrayList<String>): Boolean {
    var elementText = ""
    try {
        elementText = element.text
        val test = element.getAttribute("class")
    } catch(e: Exception) {
//            e.printStackTrace()
        return false
    }
    var containsText = false
    try {
        if(elementText == "" && element.tagName == "span") return false
        for (searchText in searchTexts) {
            if(elementText.contains(searchText) || element.findElements(By.xpath(".//*[contains(text(), '$searchText')]")).isNotEmpty()) {
                containsText = true
                break
            }
        }
    } catch(e: Exception) {
//            e.printStackTrace()
        return false
    }
    return containsText
}
private fun evaluatePageType(defaultUrl: String, driver: WebDriver): String {

    val url = driver.currentUrl
    var pageType = "unknown-page"
    if (url == "data:," || url == "") {
        driver.get(defaultUrl)
        Thread.sleep(2000)
    }
    val regex = Regex("^(https?://[a-zA-Z0-9.-]+)/?$")
    if(regex.matches(url)) return "landing-page"
    if(testProductDetailPageType(driver, driver.currentUrl)) return "product-page"

    var allATags: MutableList<WebElement>? = null

    try {
        allATags = driver.findElements(By.cssSelector("a"))
        // Sort elements based on x or y value with specified conditions
        // Print the sorted elements
        if(allATags == null) return ""
        val allLinks = ArrayList<LinkData>()
        for(element in allATags) {
            try{
                if(element.getAttribute("href") == null) continue
            } catch(e: Exception) { continue }
            val elSizes = detectAnchorElementSize(element)
            if(elSizes.height >= 60 && elSizes.width >= 60) allLinks.add(LinkData(element.location.x, element.location.y, element.getAttribute("href"), elSizes.width, elSizes.height, 0.0, 0.0))
        }
        val sortedByX = allLinks.sortedBy { it.x }
        val sortedByY = allLinks.sortedBy { it.y }
        val filteredElementListX = filterLinkListBySizeVariance(sortedByX, "x")
        val filteredElementListY = filterLinkListBySizeVariance(sortedByY, "y")
        if(filteredElementListY.size >= 3 || filteredElementListX.size >= 3) pageType = "list-page"
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return pageType
}
private fun extractDomainFromUrl(url: String): String {
//    val pattern = "^(?:https?:\\/\\/)?(?:[^@\\n]+@)?(?:www\\.)?([^:\\/?\\n]+)".toRegex()
    val pattern = "^(?:https?://)?(?:[^@\\n]+@)?(?:www\\.)?([^:/?\\n]+)".toRegex()
    val matchResult = pattern.find(url)
    val result = matchResult?.groupValues?.get(1)
    return result ?: ""
}
private fun calculateSizeScore(size: Double, maxScore: Double):Double {
//        Normal - carousel
    val ten = maxScore/4
//        Big Thumbnail
    val eight = maxScore/17
//        Medium Thumbnail
    val seven = maxScore/34
//        Small Thumbnail
    val six = maxScore/44
//       Medium Button
    val two = maxScore/160
//        Almost undetectable elements
    val zero = 10.0
    if(size > ten) return 10.0
    if(size < 10.0) return 0.0
    if(size < ten && size > eight) {
        val pointA = Point2D.Double(ten, 10.0)
        val pointB = Point2D.Double(eight, 8.0)
        return interpolateY(pointA, pointB, size)

    } else if(size < eight && size >= seven){
        val pointA = Point2D.Double(eight, 8.0)
        val pointB = Point2D.Double(seven, 7.0)
        return interpolateY(pointA, pointB, size)
    }
    else if(size < seven && size >= six){
        val pointA = Point2D.Double(seven, 7.0)
        val pointB = Point2D.Double(six, 6.0)
        return interpolateY(pointA, pointB, size)
    }
    else if(size < six && size >= two){
        val pointA = Point2D.Double(six, 6.0)
        val pointB = Point2D.Double(two, 2.0)
        return interpolateY(pointA, pointB, size)
    }
    else if(size < two){
        val pointA = Point2D.Double(two, 2.0)
        val pointB = Point2D.Double(zero, 0.0)
        return interpolateY(pointA, pointB, size)
    } else {
        println("bug at size $size, $maxScore")
        return 0.0
    }
}
private fun calculateLocationScore(locX: Double, locY: Double, clientWidth: Double, clientHeight: Double, pageHeight: Double, headerHeight: Double): Double {
    if(locY < headerHeight) return 0.0
    // X SCORE
    val centerX = clientWidth * 0.5
    // Right Half of the Page
    var xScore = 8.0
    // Left Half of the Page
    if(locX < centerX) {
        xScore = 10.0
    }
    // Y SCORE
    // Top 90% of the page
    val lower90 = clientHeight * 0.9
    var yScore = 0.0
    if(locY < lower90) {
        yScore = 10.0
    } else if(locY > clientHeight) {
        // Linear Y Score from clientHeight to Bottom Page
        val pointA = Point2D.Double(clientHeight, 8.0)
        val pointB = Point2D.Double(pageHeight, 1.0)
        yScore = interpolateY(pointA, pointB, locY)
    } else if(locY >= lower90) {
        // Low 90% Y Score
        yScore = 8.0
    } else {
        println("bug at locY $locY, lower90: $lower90")
    }
    return ((yScore + xScore) / 2)
}
private fun getAllLinkData(domain: String, driver: WebDriver): ArrayList<LinkData> {
//        Refactored
    /* *** Setup Arrays + Test current URL for domain **** */
    if(!driver.currentUrl.contains(domain)) return ArrayList<LinkData>()
    var allATags: MutableList<WebElement>? = null
    val allLinks = ArrayList<LinkData>()
    var headerHeight = 0.0
    val clientWidth = 1920.0
    val clientHeight = 930.0
    /* *** Reference all anchor elements **** */
    try {
        headerHeight = driver.findElement(By.tagName("header")).size.height.toDouble()
        allATags = driver.findElements(By.cssSelector("a"))
    } catch (e: Exception) {
        println("aTags error $e")
    }
    /* *** Loop through all anchor elements **** */
    if (allATags != null) {
        for (aTag in allATags) {
            /* *** Skip stale elements **** */
            var href: String? = null
            try {
                href = aTag.getAttribute("href")
                if (href == null) continue
            } catch (e: Exception) {
                continue
            }
            /* *** Only inside the domain **** */
            if (href == "") continue
            if (!href.contains(domain)) continue
            /* *** Get location and size properties **** */
            val x = aTag.location.x
            val y = aTag.location.y
            val sizes = detectAnchorElementSize(aTag)
            val width = sizes.width
            val height = sizes.height
            /* *** Ignore locations that ar negative or inside the navigation bar **** */
            if(x <= 0 || x > clientWidth || y < headerHeight) continue

            /* *** Detect invisible elements. **** */
            var visibility = aTag.getCssValue("visibility")
            if (height == 0 || width == 0) visibility = "hidden"
            if(visibility == "hidden") continue
            /* *** Detect elements from the navigation bar **** */
            /* *** Calculate Scores **** */
            val pageHeight = driver.findElement(By.tagName("body")).size.height.toDouble()
            val sizeScore = calculateSizeScore((width * height).toDouble(), (clientHeight * clientWidth))
            val locScore = calculateLocationScore(x.toDouble(), y.toDouble(), clientWidth, clientHeight, pageHeight, headerHeight)
            /* *** Add the link that fulfills all requirements (except deep page load link testing) **** */
            allLinks.add(
                LinkData(
                    x,
                    y,
                    href,
                    height,
                    width,
                    locScore,
                    sizeScore
                )
            )
        }
    }
    return allLinks
}
private fun getFinalLink(sortedLinks: List<LinkData>, sessionLinks: ArrayList<Link>, blackList: ArrayList<Link>, domain: String, sessionIndex: Int, maxSessionIndex: Int, driver: WebDriver): Link? {
//        Refactored
    var finalLink: Link? = null
    val urlTemp = driver.currentUrl
    val titleTemp = driver.title
    var tryCounter = 1
    sortLinks@for (bestLink in sortedLinks) {
        finalLink = null
        /* *** Break if too many tries are necessary, to prevent spam. */
        if(tryCounter == 10) break
        driver.get(bestLink.href)
        Thread.sleep(2000)
        val title = driver.title
        val currentUrl = driver.currentUrl
        println("currentUrl $currentUrl")
        println("maxSessionIndex $maxSessionIndex")
        /* *** Load the page inside the Url and test of it's a redirect to an external page (except the last entry) **** */
        if(!currentUrl.contains(domain) && sessionIndex != (maxSessionIndex) ) continue@sortLinks
        tryCounter++
        /* *** No doubled URLs from the already generated journey *** */
        if(bestLink.href == urlTemp) continue@sortLinks
        for (existingJourneyLinks in sessionLinks) if(bestLink.href == existingJourneyLinks.currentUrl) continue@sortLinks
        /* *** No URLs from the blackList **** */
        for (blackLink in blackList) if(bestLink.href == blackLink.currentUrl) continue@sortLinks
        /* *** Get the page type (at the end, because of the poor performance) *** */
        val linkPageType = evaluatePageType(driver.currentUrl, driver)
        /* *** No doubled page titles rom the already generated journey **** */
        /* *** Possible Case: The URL is different, but the product is exactly the same. **** */
        if(title == titleTemp) continue@sortLinks
        for (existingJourneyLinks in sessionLinks) if(linkPageType == "product-page" && existingJourneyLinks.pageType == "product-page" && title == existingJourneyLinks.title) continue@sortLinks
        /* *** No titles from the blackList **** */
        for (blackLink in blackList) if(linkPageType == "product-page" && blackLink.pageType == "product-page" && title == blackLink.title) continue@sortLinks
        /* *** Every requirement has been passed, final link has been found **** */
        finalLink = Link(urlTemp, bestLink.href, linkPageType, sessionIndex, bestLink.x,  bestLink.y,  bestLink.width,  bestLink.height, bestLink.locationScore, bestLink.sizeScore, title)
        println(finalLink)
        break@sortLinks
    }
    return finalLink
}
private fun interpolateY(point1: Point2D, point2: Point2D.Double, coordinateC: Double): Double {
    val xCord1 = point1.x
    val yCord1 = point1.y
    val xCord2 = point2.x
    val yCord2 = point2.y
    val result =  yCord1 + ((coordinateC - xCord1) * (yCord2 - yCord1) / (xCord2 - xCord1))
    if(result >= 10.0 || result < 0.0) {
        println("bug at interpolationY function")
        println("xCord1 $xCord1, yCord1 $yCord1, xCord2 $xCord2, yB $yCord2")
        println("result: $result")
        return 0.0
    }
    return result
}
private fun detectAnchorElementSize(aTag: WebElement): LinkSize {
    var height = aTag.size.height
    var width = aTag.size.width
    /* ***
    * Possible case, where the anchor element has not the correct size.
    * Replacing the height or width attribute when the children are bigger in size then the parent.
    * *** */
    val childElements: List<WebElement> = aTag.findElements(By.cssSelector("*"))
    for (childElement in childElements) {
        var test: String? = null
        try {
            test = childElement.getAttribute("height")
        } catch (e: Exception) {
            continue
        }
        if (test == null) continue
        if (height == 0 || width == 0) {
            val h = childElement.size.height
            val w = childElement.size.width
            if (height < h) height = h
            if (width < w) width = w
        }
    }
    return LinkSize(width, height)
}

/* *** Main *** */
fun main() {
    val hash = generateRandomHashOfSixCharacters()
    val totalStartTime = System.currentTimeMillis()
    val rawFilePath = "$systemPath\\1_week_nov_23.csv"
    val folderPath = "$systemPath\\"

    val dbPath = "jdbc:sqlite:${folderPath}database_$hash.db"
//    val dbPath = "jdbc:sqlite:${folderPath}database_final_scrape.db"
    // Execute Task
    try {
        generateGenericUserJourneys(dbPath)
        formatRawToSession(rawFilePath, dbPath)
    } catch(e: Exception) {
        e.printStackTrace()
    }
    val totalEndTime = System.currentTimeMillis()
    val totalTime = ((totalEndTime - totalStartTime) / 1000.0)
    println("totalTime $totalTime seconds.")
}