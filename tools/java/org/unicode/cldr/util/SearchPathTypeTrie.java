package org.unicode.cldr.util;

import java.util.HashMap;
import java.util.Map;

import org.unicode.cldr.util.LogicalGrouping.PathType;

/**
 * Use Trie to search PathType
 */
public class SearchPathTypeTrie {

    public static SearchPathTypeTrie SINGLETON = new SearchPathTypeTrie();

    public SearchPathTypeTrie () {
        buildTrie();
    }

    class TrieNode {
        TrieNode[] children = new TrieNode[255];
        PathType pathType;
    }

    private TrieNode root;

    private void buildTrie() {
        root = new TrieNode();
        Map<String, PathType> map = new HashMap();
        map.put("/metazone", PathType.METAZONE);
        map.put("/days", PathType.DAYS);
        map.put("/dayPeriods", PathType.DAY_PERIODS);
        map.put("/quarters", PathType.QUARTERS);
        map.put("/months", PathType.MONTHS);
        map.put("/relative[", PathType.RELATIVE);
        map.put("/decimalFormatLength", PathType.DECIMAL_FORMAT_LENGTH);
        map.put("[@gender=", PathType.GENDER);
        map.put("[@case=", PathType.CASE);
        map.put("[@count=", PathType.COUNT);
        TrieNode curr = root;
        for (String word: map.keySet()) {
            curr = root;
            for (char c: word.toCharArray()) {
                if (curr.children[c] == null) {
                    curr.children[c] = new TrieNode();
                }
                curr = curr.children[c];
            }
            curr.pathType = map.get(word);
        }
    }

    private PathType search(String path, int start) {
        TrieNode curr = root;
        for (int i = start; i < path.length(); i++) {
            char c = path.charAt(i);
            if (curr.children[c] != null) {
                curr = curr.children[c];
                if (curr.pathType != null) {
                    return curr.pathType;
                }
            } else {
                break;
            }
        }
        return null;
    }

    public PathType getPathType(String path) {
        PathType res = PathType.SINGLETON;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c >= 0 && c < 255 && root.children[c] != null) {
                PathType type = search(path, i);
                if (type != null) {
                    return type;
                }
            }
        }
        return res;
    }

}
