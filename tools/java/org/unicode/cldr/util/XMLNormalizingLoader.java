package org.unicode.cldr.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.unicode.cldr.util.CLDRFile.DraftStatus;
import org.unicode.cldr.util.XMLFileReader.AllHandler;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.ibm.icu.impl.Utility;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.util.ICUUncheckedIOException;
import com.ibm.icu.util.VersionInfo;

public class XMLNormalizingLoader{
    private static class XMLSourceCacheKey {
        String localeId;
        List<File> dirs;
        DraftStatus minimalDraftStatus;
        int hashCode;
        public XMLSourceCacheKey(String localeId, List<File> dirs, DraftStatus minimalDraftStatus) {
            this.localeId = localeId;
            this.dirs = dirs;
            this.minimalDraftStatus = minimalDraftStatus;
            this.hashCode = Objects.hash(this.localeId, this.dirs, this.minimalDraftStatus);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            XMLSourceCacheKey other = (XMLSourceCacheKey) obj;
            if (!Objects.equals(dirs, other.dirs)) {
                return false;
            }
            if (minimalDraftStatus != other.minimalDraftStatus) {
                return false;
            }
            if (localeId == null) {
                if (other.localeId != null) {
                    return false;
                }
            } else if (!localeId.equals(other.localeId)) {
                return false;
            }
            return true;
        }
    }

    private static final int CACHE_LIMIT = 1500;
    private static LoadingCache<XMLSourceCacheKey, XMLSource> cache = CacheBuilder.newBuilder()
        .maximumSize(CACHE_LIMIT)
        .softValues()
        .build(
            new CacheLoader<XMLSourceCacheKey, XMLSource>() {
                @Override
                public XMLSource load(XMLSourceCacheKey key) {
                    return makeXMLSource(key);
                }
            });

    public static XMLSource makeXMLSource(XMLSourceCacheKey key) {
        XMLSource source = null;
        List<XMLSource> list = new ArrayList<>();
        if (key.dirs.size() == 1) {
            File file = new File(key.dirs.get(0), key.localeId + ".xml");
            source = loadXMLFile(file, key.localeId, key.minimalDraftStatus);
            source.freeze();
            return source;
        }

        List<File> dirList = new ArrayList<>();
        for (File dir: key.dirs) {
            dirList.clear();
            dirList.add(dir);
            XMLSourceCacheKey singleKey = new XMLSourceCacheKey(key.localeId, dirList, key.minimalDraftStatus);
            XMLSource singleSource = null;
            singleSource = cache.getUnchecked(singleKey);
            list.add(singleSource);
        }

        source = list.get(0).cloneAsThawed();
        for (int i = 1; i < list.size(); i++) {
            XMLSource other = list.get(i);
            source.putAll(other, 0); // 0 --> merge_keep_mine
            source.getXpathComments().joinAll(other.getXpathComments());
        }
        source.freeze();
        return source;
    }

    public static XMLSource getFrozenInstance(String localeId, List<File> dirs, DraftStatus minimalDraftStatus) {
        XMLSourceCacheKey key = new XMLSourceCacheKey(localeId, dirs, minimalDraftStatus);
        return cache.getUnchecked(key);
    }

    public static XMLSource loadXMLFile (File f, String localeId, DraftStatus minimalDraftStatus) {
        String fullFileName = f.getAbsolutePath();
        InputStream fis = null;
        try {
            fullFileName = f.getCanonicalPath();
            fis = InputStreamFactory.createInputStream(f);
        } catch (IOException e) {
            StringBuilder sb = new StringBuilder("Cannot read the file '");
            sb.append(f);
            throw new ICUUncheckedIOException(sb.toString(), e);
        }

        fis = new StripUTF8BOMInputStream(fis);
        InputStreamReader reader = new InputStreamReader(fis, Charset.forName("UTF-8"));
        XMLSource source = new SimpleXMLSource(localeId);
        XMLNormalizingHandler XML_HANDLER = new XMLNormalizingHandler(source, minimalDraftStatus);
        XMLFileReader.read(fullFileName, reader, -1, true, XML_HANDLER);
        if (XML_HANDLER.isSupplemental < 0) {
            throw new IllegalArgumentException("root of file must be either ldml or supplementalData");
        }
        source.setNonInheriting(XML_HANDLER.isSupplemental > 0);
        if (XML_HANDLER.overrideCount > 0) {
            throw new IllegalArgumentException("Internal problems: either data file has duplicate path, or" +
                " CLDRFile.isDistinguishing() or CLDRFile.isOrdered() need updating: "
                + XML_HANDLER.overrideCount
                + "; The exact problems are printed on the console above.");
        }
        return source;
    }

    public static boolean LOG_PROGRESS = false;
    private static final boolean DEBUG = false;

    private static class XMLNormalizingHandler implements AllHandler {
        private static UnicodeSet whitespace = new UnicodeSet("[:whitespace:]");
        private DraftStatus minimalDraftStatus;
        private static final boolean SHOW_START_END = false;
        private int commentStack;
        private boolean justPopped = false;
        private String lastChars = "";
        private String currentFullXPath = "/";
        private String comment = null;
        private Map<String, String> attributeOrder;
        private DtdData dtdData;
        private XMLSource source;
        private String lastActiveLeafNode;
        private String lastLeafNode;
        private int isSupplemental = -1;
        private int depth = 30; // just make deep enough to handle any CLDR file.
        private int[] orderedCounter = new int[depth];
        private String[] orderedString = new String[depth];
        private int level = 0;
        private int overrideCount = 0;

        XMLNormalizingHandler(XMLSource source, DraftStatus minimalDraftStatus) {
            this.source = source;
            this.minimalDraftStatus = minimalDraftStatus;
        }

        private String show(Attributes attributes) {
            if (attributes == null) return "null";
            String result = "";
            for (int i = 0; i < attributes.getLength(); ++i) {
                String attribute = attributes.getQName(i);
                String value = attributes.getValue(i);
                result += "[@" + attribute + "=\"" + value + "\"]"; // TODO quote the value??
            }
            return result;
        }

        private void push(String qName, Attributes attributes) {
            // SHOW_ALL &&
            Log.logln(LOG_PROGRESS, "push\t" + qName + "\t" + show(attributes));
            ++level;
            if (!qName.equals(orderedString[level])) {
                // orderedCounter[level] = 0;
                orderedString[level] = qName;
            }
            if (lastChars.length() != 0) {
                if (whitespace.containsAll(lastChars))
                    lastChars = "";
                else
                    throw new IllegalArgumentException("Must not have mixed content: " + qName + ", "
                        + show(attributes) + ", Content: " + lastChars);
            }

            currentFullXPath += "/" + qName;
            if (dtdData.isOrdered(qName)) {
                currentFullXPath += orderingAttribute();
            }
            if (attributes.getLength() > 0) {
                attributeOrder.clear();
                for (int i = 0; i < attributes.getLength(); ++i) {
                    String attribute = attributes.getQName(i);
                    String value = attributes.getValue(i);

                    if (attribute.equals("cldrVersion")
                        && (qName.equals("version"))) {
                        ((SimpleXMLSource) source).setDtdVersionInfo(VersionInfo.getInstance(value));
                    } else {
                        putAndFixDeprecatedAttribute(qName, attribute, value);
                    }
                }
                for (Iterator<String> it = attributeOrder.keySet().iterator(); it.hasNext();) {
                    String attribute = it.next();
                    String value = attributeOrder.get(attribute);
                    String both = "[@" + attribute + "=\"" + value + "\"]"; // TODO quote the value??
                    currentFullXPath += both;
                    // distinguishing = key, registry, alt, and type (except for the type attribute on the elements
                    // default and mapping).
                    // if (isDistinguishing(qName, attribute)) {
                    // currentXPath += both;
                    // }
                }
            }
            if (comment != null) {
                if (currentFullXPath.equals("//ldml") || currentFullXPath.equals("//supplementalData")) {
                    source.setInitialComment(comment);
                } else {
                    source.addComment(currentFullXPath, comment, XPathParts.Comments.CommentType.PREBLOCK);
                }
                comment = null;
            }
            justPopped = false;
            lastActiveLeafNode = null;
            Log.logln(LOG_PROGRESS, "currentFullXPath\t" + currentFullXPath);
        }

        private String orderingAttribute() {
            return "[@_q=\"" + (orderedCounter[level]++) + "\"]";
        }

        private void putAndFixDeprecatedAttribute(String element, String attribute, String value) {
            if (attribute.equals("draft")) {
                if (value.equals("true"))
                    value = "approved";
                else if (value.equals("false")) value = "unconfirmed";
            } else if (attribute.equals("type")) {
                if (changedTypes.contains(element) && isSupplemental < 1) { // measurementSystem for example did not
                    // change from 'type' to 'choice'.
                    attribute = "choice";
                }
            }

            attributeOrder.put(attribute, value);
        }

        /**
         * Types which changed from 'type' to 'choice', but not in supplemental data.
         */
        private static Set<String> changedTypes = new HashSet<>(Arrays.asList(new String[] {
            "abbreviationFallback",
            "default", "mapping", "measurementSystem", "preferenceOrdering" }));

        static final Pattern draftPattern = PatternCache.get("\\[@draft=\"([^\"]*)\"\\]");
        Matcher draftMatcher = draftPattern.matcher("");

        /**
         * Adds a parsed XPath to the CLDRFile.
         *
         * @param fullXPath
         * @param value
         */
        private void addPath(String fullXPath, String value) {
            String former = source.getValueAtPath(fullXPath);
            if (former != null) {
                String formerPath = source.getFullXPath(fullXPath);
                if (!former.equals(value) || !fullXPath.equals(formerPath)) {
                    if (!fullXPath.startsWith("//ldml/identity/version") && !fullXPath.startsWith("//ldml/identity/generation")) {
                        warnOnOverride(former, formerPath);
                    }
                }
            }
            value = trimWhitespaceSpecial(value);
            source.add(fullXPath, value);
        }

        private void pop(String qName) {
            Log.logln(LOG_PROGRESS, "pop\t" + qName);
            --level;

            if (lastChars.length() != 0 || justPopped == false) {
                boolean acceptItem = minimalDraftStatus == DraftStatus.unconfirmed;
                if (!acceptItem) {
                    if (draftMatcher.reset(currentFullXPath).find()) {
                        DraftStatus foundStatus = DraftStatus.valueOf(draftMatcher.group(1));
                        if (minimalDraftStatus.compareTo(foundStatus) <= 0) {
                            // what we found is greater than or equal to our status
                            acceptItem = true;
                        }
                    } else {
                        acceptItem = true; // if not found, then the draft status is approved, so it is always ok
                    }
                }
                if (acceptItem) {
                    // Change any deprecated orientation attributes into values
                    // for backwards compatibility.
                    boolean skipAdd = false;
                    if (currentFullXPath.startsWith("//ldml/layout/orientation")) {
                        XPathParts parts = XPathParts.getFrozenInstance(currentFullXPath);
                        String value = parts.getAttributeValue(-1, "characters");
                        if (value != null) {
                            addPath("//ldml/layout/orientation/characterOrder", value);
                            skipAdd = true;
                        }
                        value = parts.getAttributeValue(-1, "lines");
                        if (value != null) {
                            addPath("//ldml/layout/orientation/lineOrder", value);
                            skipAdd = true;
                        }
                    }
                    if (!skipAdd) {
                        addPath(currentFullXPath, lastChars);
                    }
                    lastLeafNode = lastActiveLeafNode = currentFullXPath;
                }
                lastChars = "";
            } else {
                Log.logln(LOG_PROGRESS && lastActiveLeafNode != null, "pop: zeroing last leafNode: "
                    + lastActiveLeafNode);
                lastActiveLeafNode = null;
                if (comment != null) {
                    source.addComment(lastLeafNode, comment, XPathParts.Comments.CommentType.POSTBLOCK);
                    comment = null;
                }
            }
            // currentXPath = stripAfter(currentXPath, qName);
            currentFullXPath = stripAfter(currentFullXPath, qName);
            justPopped = true;
        }

        static Pattern WHITESPACE_WITH_LF = PatternCache.get("\\s*\\u000a\\s*");
        Matcher whitespaceWithLf = WHITESPACE_WITH_LF.matcher("");
        static final UnicodeSet CONTROLS = new UnicodeSet("[:cc:]");

        /**
         * Trim leading whitespace if there is a linefeed among them, then the same with trailing.
         *
         * @param source
         * @return
         */
        private String trimWhitespaceSpecial(String source) {
            if (DEBUG && CONTROLS.containsSome(source)) {
                System.out.println("*** " + source);
            }
            if (!source.contains("\n")) {
                return source;
            }
            source = whitespaceWithLf.reset(source).replaceAll("\n");
            return source;
        }

        private void warnOnOverride(String former, String formerPath) {
            String distinguishing = CLDRFile.getDistinguishingXPath(formerPath, null);
            System.out.println("\tERROR in " + source.getLocaleID()
                + ";\toverriding old value <" + former + "> at path " + distinguishing +
                "\twith\t<" + lastChars + ">" +
                CldrUtility.LINE_SEPARATOR + "\told fullpath: " + formerPath +
                CldrUtility.LINE_SEPARATOR + "\tnew fullpath: " + currentFullXPath);
            overrideCount += 1;
        }

        private static String stripAfter(String input, String qName) {
            int pos = findLastSlash(input);
            if (qName != null) {
                // assert input.substring(pos+1).startsWith(qName);
                if (!input.substring(pos + 1).startsWith(qName)) {
                    throw new IllegalArgumentException("Internal Error: should never get here.");
                }
            }
            return input.substring(0, pos);
        }

        private static int findLastSlash(String input) {
            int braceStack = 0;
            char inQuote = 0;
            for (int i = input.length() - 1; i >= 0; --i) {
                char ch = input.charAt(i);
                switch (ch) {
                case '\'':
                case '"':
                    if (inQuote == 0) {
                        inQuote = ch;
                    } else if (inQuote == ch) {
                        inQuote = 0; // come out of quote
                    }
                    break;
                case '/':
                    if (inQuote == 0 && braceStack == 0) {
                        return i;
                    }
                    break;
                case '[':
                    if (inQuote == 0) {
                        --braceStack;
                    }
                    break;
                case ']':
                    if (inQuote == 0) {
                        ++braceStack;
                    }
                    break;
                }
            }
            return -1;
        }

        // SAX items we need to catch

        @Override
        public void startElement(
            String uri,
            String localName,
            String qName,
            Attributes attributes)
            throws SAXException {
            Log.logln(LOG_PROGRESS || SHOW_START_END, "startElement uri\t" + uri
                + "\tlocalName " + localName
                + "\tqName " + qName
                + "\tattributes " + show(attributes));
            try {
                if (isSupplemental < 0) { // set by first element
                    attributeOrder = new TreeMap<>(
                        // HACK for ldmlIcu
                        dtdData.dtdType == DtdType.ldml
                            ? CLDRFile.getAttributeOrdering()
                            : dtdData.getAttributeComparator());
                    isSupplemental = source.getXMLSourceDtdType() == DtdType.ldml ? 0 : 1;
                }
                push(qName, attributes);
            } catch (RuntimeException e) {
                e.printStackTrace();
                throw e;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName)
            throws SAXException {
            Log.logln(LOG_PROGRESS || SHOW_START_END, "endElement uri\t" + uri + "\tlocalName " + localName
                + "\tqName " + qName);
            try {
                pop(qName);
            } catch (RuntimeException e) {
                // e.printStackTrace();
                throw e;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length)
            throws SAXException {
            try {
                String value = new String(ch, start, length);
                Log.logln(LOG_PROGRESS, "characters:\t" + value);
                // we will strip leading and trailing line separators in another place.
                // if (value.indexOf(XML_LINESEPARATOR) >= 0) {
                // value = value.replace(XML_LINESEPARATOR, '\u0020');
                // }
                lastChars += value;
                justPopped = false;
            } catch (RuntimeException e) {
                e.printStackTrace();
                throw e;
            }
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
            Log.logln(LOG_PROGRESS, "startDTD name: " + name
                + ", publicId: " + publicId
                + ", systemId: " + systemId);
            commentStack++;
            source.setDtdType(DtdType.valueOf(name));
            dtdData = DtdData.getInstance(source.getXMLSourceDtdType());
        }

        @Override
        public void endDTD() throws SAXException {
            Log.logln(LOG_PROGRESS, "endDTD");
            commentStack--;
        }

        @Override
        public void comment(char[] ch, int start, int length) throws SAXException {
            final String string = new String(ch, start, length);
            Log.logln(LOG_PROGRESS, commentStack + " comment " + string);
            try {
                if (commentStack != 0) return;
                String comment0 = trimWhitespaceSpecial(string).trim();
                if (lastActiveLeafNode != null) {
                    source.addComment(lastActiveLeafNode, comment0, XPathParts.Comments.CommentType.LINE);
                } else {
                    comment = (comment == null ? comment0 : comment + XPathParts.NEWLINE + comment0);
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
                throw e;
            }
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            if (LOG_PROGRESS)
                Log.logln(LOG_PROGRESS,
                    "ignorableWhitespace length: " + length + ": " + Utility.hex(new String(ch, start, length)));
            for (int i = start; i < start + length; ++i) {
                if (ch[i] == '\n') {
                    Log.logln(LOG_PROGRESS && lastActiveLeafNode != null, "\\n: zeroing last leafNode: "
                        + lastActiveLeafNode);
                    lastActiveLeafNode = null;
                    break;
                }
            }
        }

        @Override
        public void startDocument() throws SAXException {
            Log.logln(LOG_PROGRESS, "startDocument");
            commentStack = 0; // initialize
        }

        @Override
        public void endDocument() throws SAXException {
            Log.logln(LOG_PROGRESS, "endDocument");
            try {
                if (comment != null) source.addComment(null, comment, XPathParts.Comments.CommentType.LINE);
            } catch (RuntimeException e) {
                e.printStackTrace();
                throw e;
            }
        }

        // ==== The following are just for debuggin =====

        @Override
        public void elementDecl(String name, String model) throws SAXException {
            Log.logln(LOG_PROGRESS, "Attribute\t" + name + "\t" + model);
        }

        @Override
        public void attributeDecl(String eName, String aName, String type, String mode, String value)
            throws SAXException {
            Log.logln(LOG_PROGRESS, "Attribute\t" + eName + "\t" + aName + "\t" + type + "\t" + mode + "\t" + value);
        }

        @Override
        public void internalEntityDecl(String name, String value) throws SAXException {
            Log.logln(LOG_PROGRESS, "Internal Entity\t" + name + "\t" + value);
        }

        @Override
        public void externalEntityDecl(String name, String publicId, String systemId) throws SAXException {
            Log.logln(LOG_PROGRESS, "Internal Entity\t" + name + "\t" + publicId + "\t" + systemId);
        }

        @Override
        public void processingInstruction(String target, String data)
            throws SAXException {
            Log.logln(LOG_PROGRESS, "processingInstruction: " + target + ", " + data);
        }

        @Override
        public void skippedEntity(String name)
            throws SAXException {
            Log.logln(LOG_PROGRESS, "skippedEntity: " + name);
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            Log.logln(LOG_PROGRESS, "setDocumentLocator Locator " + locator);
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            Log.logln(LOG_PROGRESS, "startPrefixMapping prefix: " + prefix +
                ", uri: " + uri);
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            Log.logln(LOG_PROGRESS, "endPrefixMapping prefix: " + prefix);
        }

        @Override
        public void startEntity(String name) throws SAXException {
            Log.logln(LOG_PROGRESS, "startEntity name: " + name);
        }

        @Override
        public void endEntity(String name) throws SAXException {
            Log.logln(LOG_PROGRESS, "endEntity name: " + name);
        }

        @Override
        public void startCDATA() throws SAXException {
            Log.logln(LOG_PROGRESS, "startCDATA");
        }

        @Override
        public void endCDATA() throws SAXException {
            Log.logln(LOG_PROGRESS, "endCDATA");
        }

        /*
         * (non-Javadoc)
         *
         * @see org.xml.sax.ErrorHandler#error(org.xml.sax.SAXParseException)
         */
        @Override
        public void error(SAXParseException exception) throws SAXException {
            Log.logln(LOG_PROGRESS || true, "error: " + XMLFileReader.showSAX(exception));
            throw exception;
        }

        /*
         * (non-Javadoc)
         *
         * @see org.xml.sax.ErrorHandler#fatalError(org.xml.sax.SAXParseException)
         */
        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            Log.logln(LOG_PROGRESS, "fatalError: " + XMLFileReader.showSAX(exception));
            throw exception;
        }

        /*
         * (non-Javadoc)
         *
         * @see org.xml.sax.ErrorHandler#warning(org.xml.sax.SAXParseException)
         */
        @Override
        public void warning(SAXParseException exception) throws SAXException {
            Log.logln(LOG_PROGRESS, "warning: " + XMLFileReader.showSAX(exception));
            throw exception;
        }
    }

//    public static void main(String[] args) {
//        CLDRConfig config = CLDRConfig.getInstance();
//        List<File> list = new ArrayList();
//        list.add(new File("/usr/local/google/home/yqlu/Documents/CLDRProject/cldr/common/validity"));  // language
//        XMLSource source = XMLSource.getFrozenInstance("language", list, DraftStatus.unconfirmed);
//        System.out.println("end!"  + source.getLocaleID());
//    }
}
