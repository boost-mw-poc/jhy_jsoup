package org.jsoup.nodes;

import org.jsoup.helper.Validate;
import org.jsoup.internal.QuietAppendable;
import org.jsoup.internal.SharedConstants;
import org.jsoup.internal.StringUtil;
import org.jsoup.parser.ParseSettings;
import org.jspecify.annotations.Nullable;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import static org.jsoup.internal.Normalizer.lowerCase;
import static org.jsoup.internal.SharedConstants.AttrRangeKey;
import static org.jsoup.nodes.Range.AttributeRange.UntrackedAttr;

/**
 * The attributes of an Element.
 * <p>
 * During parsing, attributes in with the same name in an element are deduplicated, according to the configured parser's
 * attribute case-sensitive setting. It is possible to have duplicate attributes subsequently if
 * {@link #add(String, String)} vs {@link #put(String, String)} is used.
 * </p>
 * <p>
 * Attribute name and value comparisons are generally <b>case sensitive</b>. By default for HTML, attribute names are
 * normalized to lower-case on parsing. That means you should use lower-case strings when referring to attributes by
 * name.
 * </p>
 *
 * @author Jonathan Hedley, jonathan@hedley.net
 */
public class Attributes implements Iterable<Attribute>, Cloneable {
    // The Attributes object is only created on the first use of an attribute; the Element will just have a null
    // Attribute slot otherwise

    static final char InternalPrefix = '/'; // Indicates an internal key. Can't be set via HTML. (It could be set via accessor, but not too worried about that. Suppressed from list, iter, size.)
    protected static final String dataPrefix = "data-"; // data attributes
    private static final String EmptyString = "";

    // manages the key/val arrays
    private static final int InitialCapacity = 3; // sampling found mean count when attrs present = 1.49; 1.08 overall. 2.6:1 don't have any attrs.
    private static final int GrowthFactor = 2;
    static final int NotFound = -1;

    // the number of instance fields is kept as low as possible giving an object size of 24 bytes
    int size = 0; // number of slots used (not total capacity, which is keys.length). Package visible for actual size (incl internal)
    @Nullable String[] keys = new String[InitialCapacity]; // keys is not null, but contents may be. Same for vals
    @Nullable Object[] vals = new Object[InitialCapacity]; // Genericish: all non-internal attribute values must be Strings and are cast on access.
    // todo - make keys iterable without creating Attribute objects

    // check there's room for more
    private void checkCapacity(int minNewSize) {
        Validate.isTrue(minNewSize >= size);
        int curCap = keys.length;
        if (curCap >= minNewSize)
            return;
        int newCap = curCap >= InitialCapacity ? size * GrowthFactor : InitialCapacity;
        if (minNewSize > newCap)
            newCap = minNewSize;

        keys = Arrays.copyOf(keys, newCap);
        vals = Arrays.copyOf(vals, newCap);
    }

    int indexOfKey(String key) {
        Validate.notNull(key);
        for (int i = 0; i < size; i++) {
            if (key.equals(keys[i]))
                return i;
        }
        return NotFound;
    }

    private int indexOfKeyIgnoreCase(String key) {
        Validate.notNull(key);
        for (int i = 0; i < size; i++) {
            if (key.equalsIgnoreCase(keys[i]))
                return i;
        }
        return NotFound;
    }

    // we track boolean attributes as null in values - they're just keys. so returns empty for consumers
    // casts to String, so only for non-internal attributes
    static String checkNotNull(@Nullable Object val) {
        return val == null ? EmptyString : (String) val;
    }

    /**
     Get an attribute value by key.
     @param key the (case-sensitive) attribute key
     @return the attribute value if set; or empty string if not set (or a boolean attribute).
     @see #hasKey(String)
     */
    public String get(String key) {
        int i = indexOfKey(key);
        return i == NotFound ? EmptyString : checkNotNull(vals[i]);
    }

    /**
     Get an Attribute by key. The Attribute will remain connected to these Attributes, so changes made via
     {@link Attribute#setKey(String)}, {@link Attribute#setValue(String)} etc will cascade back to these Attributes and
     their owning Element.
     @param key the (case-sensitive) attribute key
     @return the Attribute for this key, or null if not present.
     @since 1.17.2
     */
    @Nullable public Attribute attribute(String key) {
        int i = indexOfKey(key);
        return i == NotFound ? null : new Attribute(key, checkNotNull(vals[i]), this);
    }

    /**
     * Get an attribute's value by case-insensitive key
     * @param key the attribute name
     * @return the first matching attribute value if set; or empty string if not set (ora boolean attribute).
     */
    public String getIgnoreCase(String key) {
        int i = indexOfKeyIgnoreCase(key);
        return i == NotFound ? EmptyString : checkNotNull(vals[i]);
    }

    /**
     * Adds a new attribute. Will produce duplicates if the key already exists.
     * @see Attributes#put(String, String)
     */
    public Attributes add(String key, @Nullable String value) {
        addObject(key, value);
        return this;
    }

    private void addObject(String key, @Nullable Object value) {
        checkCapacity(size + 1);
        keys[size] = key;
        vals[size] = value;
        size++;
    }

    /**
     * Set a new attribute, or replace an existing one by key.
     * @param key case sensitive attribute key (not null)
     * @param value attribute value (which can be null, to set a true boolean attribute)
     * @return these attributes, for chaining
     */
    public Attributes put(String key, @Nullable String value) {
        Validate.notNull(key);
        int i = indexOfKey(key);
        if (i != NotFound)
            vals[i] = value;
        else
            addObject(key, value);
        return this;
    }

    /**
     Get the map holding any user-data associated with these Attributes. Will be created empty on first use. Held as
     an internal attribute, not a field member, to reduce the memory footprint of Attributes when not used. Can hold
     arbitrary objects; use for source ranges, connecting W3C nodes to Elements, etc.
     * @return the map holding user-data
     */
    Map<String, Object> userData() {
        final Map<String, Object> userData;
        int i = indexOfKey(SharedConstants.UserDataKey);
        if (i == NotFound) {
            userData = new HashMap<>();
            addObject(SharedConstants.UserDataKey, userData);
        } else {
            //noinspection unchecked
            userData = (Map<String, Object>) vals[i];
        }
        assert userData != null;
        return userData;
    }

    /**
     Check if these attributes have any user data associated with them.
     */
    boolean hasUserData() {
        return hasKey(SharedConstants.UserDataKey);
    }

    /**
     Get an arbitrary user-data object by key.
     * @param key case-sensitive key to the object.
     * @return the object associated to this key, or {@code null} if not found.
     * @see #userData(String key, Object val)
     * @since 1.17.1
     */
    @Nullable
    public Object userData(String key) {
        Validate.notNull(key);
        if (!hasUserData()) return null; // no user data exists
        Map<String, Object> userData = userData();
        return userData.get(key);
    }

    /**
     Set an arbitrary user-data object by key. Will be treated as an internal attribute, so will not be emitted in HTML.
     * @param key case-sensitive key
     * @param value object value. Providing a {@code null} value has the effect of removing the key from the userData map.
     * @return these attributes
     * @see #userData(String key)
     * @since 1.17.1
     */
    public Attributes userData(String key, @Nullable Object value) {
        Validate.notNull(key);
        if (value == null && !hasKey(SharedConstants.UserDataKey)) return this; // no user data exists, so short-circuit
        Map<String, Object> userData = userData();
        if (value == null)  userData.remove(key);
        else                userData.put(key, value);
        return this;
    }

    void putIgnoreCase(String key, @Nullable String value) {
        int i = indexOfKeyIgnoreCase(key);
        if (i != NotFound) {
            vals[i] = value;
            String old = keys[i];
            assert old != null;
            if (!old.equals(key)) // case changed, update
                keys[i] = key;
        }
        else
            addObject(key, value);
    }

    /**
     * Set a new boolean attribute. Removes the attribute if the value is false.
     * @param key case <b>insensitive</b> attribute key
     * @param value attribute value
     * @return these attributes, for chaining
     */
    public Attributes put(String key, boolean value) {
        if (value)
            putIgnoreCase(key, null);
        else
            remove(key);
        return this;
    }

    /**
     Set a new attribute, or replace an existing one by key.
     @param attribute attribute with case-sensitive key
     @return these attributes, for chaining
     */
    public Attributes put(Attribute attribute) {
        Validate.notNull(attribute);
        put(attribute.getKey(), attribute.getValue());
        attribute.parent = this;
        return this;
    }

    // removes and shifts up
    @SuppressWarnings("AssignmentToNull")
    private void remove(int index) {
        Validate.isFalse(index >= size);
        int shifted = size - index - 1;
        if (shifted > 0) {
            System.arraycopy(keys, index + 1, keys, index, shifted);
            System.arraycopy(vals, index + 1, vals, index, shifted);
        }
        size--;
        keys[size] = null; // release hold
        vals[size] = null;
    }

    /**
     Remove an attribute by key. <b>Case sensitive.</b>
     @param key attribute key to remove
     */
    public void remove(String key) {
        int i = indexOfKey(key);
        if (i != NotFound)
            remove(i);
    }

    /**
     Remove an attribute by key. <b>Case insensitive.</b>
     @param key attribute key to remove
     */
    public void removeIgnoreCase(String key) {
        int i = indexOfKeyIgnoreCase(key);
        if (i != NotFound)
            remove(i);
    }

    /**
     Tests if these attributes contain an attribute with this key.
     @param key case-sensitive key to check for
     @return true if key exists, false otherwise
     */
    public boolean hasKey(String key) {
        return indexOfKey(key) != NotFound;
    }

    /**
     Tests if these attributes contain an attribute with this key.
     @param key key to check for
     @return true if key exists, false otherwise
     */
    public boolean hasKeyIgnoreCase(String key) {
        return indexOfKeyIgnoreCase(key) != NotFound;
    }

    /**
     * Check if these attributes contain an attribute with a value for this key.
     * @param key key to check for
     * @return true if key exists, and it has a value
     */
    public boolean hasDeclaredValueForKey(String key) {
        int i = indexOfKey(key);
        return i != NotFound && vals[i] != null;
    }

    /**
     * Check if these attributes contain an attribute with a value for this key.
     * @param key case-insensitive key to check for
     * @return true if key exists, and it has a value
     */
    public boolean hasDeclaredValueForKeyIgnoreCase(String key) {
        int i = indexOfKeyIgnoreCase(key);
        return i != NotFound && vals[i] != null;
    }

    /**
     Get the number of attributes in this set, excluding any internal-only attributes (e.g. user data).
     <p>Internal attributes are excluded from the {@link #html()}, {@link #asList()}, and {@link #iterator()}
     methods.</p>

     @return size
     */
    public int size() {
        if (size == 0) return 0;
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (!isInternalKey(keys[i]))  count++;
        }
        return count;
    }

    /**
     Test if this Attributes list is empty.
     <p>This does not include internal attributes, such as user data.</p>
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     Add all the attributes from the incoming set to this set.
     @param incoming attributes to add to these attributes.
     */
    public void addAll(Attributes incoming) {
        int incomingSize = incoming.size(); // not adding internal
        if (incomingSize == 0) return;
        checkCapacity(size + incomingSize);

        boolean needsPut = size != 0; // if this set is empty, no need to check existing set, so can add() vs put()
        // (and save bashing on the indexOfKey()
        for (Attribute attr : incoming) {
            if (needsPut)
                put(attr);
            else
                addObject(attr.getKey(), attr.getValue());
        }
    }

    /**
     Get the source ranges (start to end position) in the original input source from which this attribute's <b>name</b>
     and <b>value</b> were parsed.
     <p>Position tracking must be enabled prior to parsing the content.</p>
     @param key the attribute name
     @return the ranges for the attribute's name and value, or {@code untracked} if the attribute does not exist or its range
     was not tracked.
     @see org.jsoup.parser.Parser#setTrackPosition(boolean)
     @see Attribute#sourceRange()
     @see Node#sourceRange()
     @see Element#endSourceRange()
     @since 1.17.1
     */
    public Range.AttributeRange sourceRange(String key) {
        if (!hasKey(key)) return UntrackedAttr;
        Map<String, Range.AttributeRange> ranges = getRanges();
        if (ranges == null) return Range.AttributeRange.UntrackedAttr;
        Range.AttributeRange range = ranges.get(key);
        return range != null ? range : Range.AttributeRange.UntrackedAttr;
    }

    /** Get the Ranges, if tracking is enabled; null otherwise. */
    @Nullable Map<String, Range.AttributeRange> getRanges() {
        //noinspection unchecked
        return (Map<String, Range.AttributeRange>) userData(AttrRangeKey);
    }

    /**
     Set the source ranges (start to end position) from which this attribute's <b>name</b> and <b>value</b> were parsed.
     @param key the attribute name
     @param range the range for the attribute's name and value
     @return these attributes, for chaining
     @since 1.18.2
     */
    public Attributes sourceRange(String key, Range.AttributeRange range) {
        Validate.notNull(key);
        Validate.notNull(range);
        Map<String, Range.AttributeRange> ranges = getRanges();
        if (ranges == null) {
            ranges = new HashMap<>();
            userData(AttrRangeKey, ranges);
        }
        ranges.put(key, range);
        return this;
    }


    @Override
    public Iterator<Attribute> iterator() {
        //noinspection ReturnOfInnerClass
        return new Iterator<Attribute>() {
            int expectedSize = size;
            int i = 0;

            @Override
            public boolean hasNext() {
                checkModified();
                while (i < size) {
                    String key = keys[i];
                    assert key != null;
                    if (isInternalKey(key)) // skip over internal keys
                        i++;
                    else
                        break;
                }

                return i < size;
            }

            @Override
            public Attribute next() {
                checkModified();
                if (i >= size) throw new NoSuchElementException();
                String key = keys[i];
                assert key != null;
                final Attribute attr = new Attribute(key, (String) vals[i], Attributes.this);
                i++;
                return attr;
            }

            private void checkModified() {
                if (size != expectedSize) throw new ConcurrentModificationException("Use Iterator#remove() instead to remove attributes while iterating.");
            }

            @Override
            public void remove() {
                Attributes.this.remove(--i); // next() advanced, so rewind
                expectedSize--;
            }
        };
    }

    /**
     Get the attributes as a List, for iteration.
     @return a view of the attributes as an unmodifiable List.
     */
    public List<Attribute> asList() {
        ArrayList<Attribute> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String key = keys[i];
            assert key != null;
            if (isInternalKey(key))
                continue; // skip internal keys
            Attribute attr = new Attribute(key, (String) vals[i], Attributes.this);
            list.add(attr);
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * Retrieves a filtered view of attributes that are HTML5 custom data attributes; that is, attributes with keys
     * starting with {@code data-}.
     * @return map of custom data attributes.
     */
    public Map<String, String> dataset() {
        return new Dataset(this);
    }

    /**
     Get the HTML representation of these attributes.
     @return HTML
     */
    public String html() {
        StringBuilder sb = StringUtil.borrowBuilder();
        html(QuietAppendable.wrap(sb), new Document.OutputSettings()); // output settings a bit funky, but this html() seldom used
        return StringUtil.releaseBuilder(sb);
    }

    final void html(final QuietAppendable accum, final Document.OutputSettings out) {
        final int sz = size;
        for (int i = 0; i < sz; i++) {
            String key = keys[i];
            assert key != null;
            if (isInternalKey(key))
                continue;
            final String validated = Attribute.getValidKey(key, out.syntax());
            if (validated != null)
                Attribute.htmlNoValidate(validated, (String) vals[i], accum.append(' '), out);
        }
    }

    @Override
    public String toString() {
        return html();
    }

    /**
     * Checks if these attributes are equal to another set of attributes, by comparing the two sets. Note that the order
     * of the attributes does not impact this equality (as per the Map interface equals()).
     * @param o attributes to compare with
     * @return if both sets of attributes have the same content
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Attributes that = (Attributes) o;
        if (size != that.size) return false;
        for (int i = 0; i < size; i++) {
            String key = keys[i];
            assert key != null;
            int thatI = that.indexOfKey(key);
            if (thatI == NotFound || !Objects.equals(vals[i], that.vals[thatI]))
                return false;
        }
        return true;
    }

    /**
     * Calculates the hashcode of these attributes, by iterating all attributes and summing their hashcodes.
     * @return calculated hashcode
     */
    @Override
    public int hashCode() {
        int result = size;
        result = 31 * result + Arrays.hashCode(keys);
        result = 31 * result + Arrays.hashCode(vals);
        return result;
    }

    @Override
    public Attributes clone() {
        Attributes clone;
        try {
            clone = (Attributes) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
        clone.size = size;
        clone.keys = Arrays.copyOf(keys, size);
        clone.vals = Arrays.copyOf(vals, size);

        // make a copy of the user data map. (Contents are shallow).
        int i = indexOfKey(SharedConstants.UserDataKey);
        if (i != NotFound) {
            //noinspection unchecked
            vals[i] = new HashMap<>((Map<String, Object>) vals[i]);
        }

        return clone;
    }

    /**
     * Internal method. Lowercases all (non-internal) keys.
     */
    public void normalize() {
        for (int i = 0; i < size; i++) {
            assert keys[i] != null;
            String key = keys[i];
            assert key != null;
            if (!isInternalKey(key))
                keys[i] = lowerCase(key);
        }
    }

    /**
     * Internal method. Removes duplicate attribute by name. Settings for case sensitivity of key names.
     * @param settings case sensitivity
     * @return number of removed dupes
     */
    public int deduplicate(ParseSettings settings) {
        if (size == 0) return 0;
        boolean preserve = settings.preserveAttributeCase();
        int dupes = 0;
        for (int i = 0; i < size; i++) {
            String keyI = keys[i];
            assert keyI != null;
            for (int j = i + 1; j < size; j++) {
                if ((preserve && keyI.equals(keys[j])) || (!preserve && keyI.equalsIgnoreCase(keys[j]))) {
                    dupes++;
                    remove(j);
                    j--;
                }
            }
        }
        return dupes;
    }

    private static class Dataset extends AbstractMap<String, String> {
        private final Attributes attributes;

        private Dataset(Attributes attributes) {
            this.attributes = attributes;
        }

        @Override
        public Set<Entry<String, String>> entrySet() {
            return new EntrySet();
        }

        @Override
        public String put(String key, String value) {
            String dataKey = dataKey(key);
            String oldValue = attributes.hasKey(dataKey) ? attributes.get(dataKey) : null;
            attributes.put(dataKey, value);
            return oldValue;
        }

        private class EntrySet extends AbstractSet<Map.Entry<String, String>> {

            @Override
            public Iterator<Map.Entry<String, String>> iterator() {
                return new DatasetIterator();
            }

            @Override
            public int size() {
                int count = 0;
                Iterator<Entry<String, String>> iter = new DatasetIterator();
                while (iter.hasNext())
                    count++;
                return count;
            }
        }

        private class DatasetIterator implements Iterator<Map.Entry<String, String>> {
            private final Iterator<Attribute> attrIter = attributes.iterator();
            private Attribute attr;
            @Override public boolean hasNext() {
                while (attrIter.hasNext()) {
                    attr = attrIter.next();
                    if (attr.isDataAttribute()) return true;
                }
                return false;
            }

            @Override public Entry<String, String> next() {
                return new Attribute(attr.getKey().substring(dataPrefix.length()), attr.getValue());
            }

            @Override public void remove() {
                attributes.remove(attr.getKey());
            }
        }
    }

    private static String dataKey(String key) {
        return dataPrefix + key;
    }

    static String internalKey(String key) {
        return InternalPrefix + key;
    }

    static boolean isInternalKey(String key) {
        return key.length() > 1 && key.charAt(0) == InternalPrefix;
    }
}
