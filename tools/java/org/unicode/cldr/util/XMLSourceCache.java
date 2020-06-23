package org.unicode.cldr.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.unicode.cldr.util.CLDRFile.DraftStatus;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.ibm.icu.util.ICUUncheckedIOException;

/**
 * Cache immutable XMLSource
 */
public class XMLSourceCache {

    public static final XMLSourceCache SINGLETON = new XMLSourceCache();

    private static final int CACHE_LIMIT = 100;

    Cache<XMLSourceCacheKey, XMLSource> cache;

    public static boolean USE_XMLSOURCE_CACHE = CldrUtility.getProperty("XMLSourceCache", true);

    public XMLSourceCache () {
        cache =  CacheBuilder.newBuilder().maximumSize(CACHE_LIMIT).build();
    }

    class XMLSourceCacheKey {
        private final String localeName;
        private final DraftStatus draftStatus;
        private final Set<String> directories;

        public XMLSourceCacheKey(String localeName, DraftStatus draftStatus, List<File> directories) {
            this.localeName = localeName;
            this.draftStatus = draftStatus;
            // Parameter check: the directory/file supplied must be non-null and readable.
            if (directories == null || directories.isEmpty()) {
                throw new ICUUncheckedIOException("Attempt to create a CLDRCacheKey with a null directory, please supply a non-null one.");
            }
            ImmutableSet.Builder<String> _directories = ImmutableSet.builder();
            for (File directory : directories) {
                if (!directory.canRead()) {
                    throw new ICUUncheckedIOException("The directory specified, " + directory.getPath() + ", cannot be read");
                }
                _directories.add(directory.toString());
            }
            this.directories = _directories.build();
        }
    }


    public XMLSource getXMLSource (String localeName, List<File> parentDirs, DraftStatus minimalDraftStatus) {
        XMLSource res;
        XMLSourceCacheKey cacheKey = new XMLSourceCacheKey(localeName, minimalDraftStatus, parentDirs);

        // Use double-check idiom
        res = cache.getIfPresent(cacheKey);

        if (res != null) {
            return res;
        }

        synchronized (cache) {
            res = cache.getIfPresent(cacheKey);
            if (res != null) {
                return res;
            }

            // make CLDRFiles created from each XMLSource and cache them
            List<XMLSource> list = new ArrayList<>();
            for (File dir: parentDirs) {
                XMLSourceCacheKey xmlSourceCacheKey = new XMLSourceCacheKey(localeName, minimalDraftStatus, Arrays.asList(new File[] {dir}));
                XMLSource xmlSource = cache.getIfPresent(xmlSourceCacheKey);
                if (xmlSource == null) {
                    xmlSource = SimpleFactory.makeFile(localeName, dir, minimalDraftStatus).dataSource;
                    // check frozen
                    if (!xmlSource.isFrozen()) {
                        xmlSource.freeze();
                    }
                    cache.put(xmlSourceCacheKey, xmlSource);
                }
                list.add(xmlSource);
            }

            if (list.size() == 1) {
                return list.get(0);
            } else {
                // merge all XMLSource into one XMLSource
                XMLSource combinedXMLSource =  list.get(0).cloneAsThawed();
                for (int i = 1; i < list.size(); i++) {
                    combinedXMLSource.putAll(list.get(i), CLDRFile.MERGE_KEEP_MINE);
                }
                combinedXMLSource.freeze();
                cache.put(cacheKey, combinedXMLSource);
                return combinedXMLSource;
            }
        }
    }



}
