package com.nextrayvision.teslatracking.places;

import java.util.Map;

/** USPS-style abbreviations: "West 41st Street" becomes "W 41st St", "Maryland" becomes "MD". */
final class UsAddress {

    private static final Map<String, String> DIRECTIONS = Map.of(
            "North", "N", "South", "S", "East", "E", "West", "W",
            "Northeast", "NE", "Northwest", "NW", "Southeast", "SE", "Southwest", "SW");

    private static final Map<String, String> SUFFIXES = Map.ofEntries(
            Map.entry("Street", "St"), Map.entry("Avenue", "Ave"), Map.entry("Road", "Rd"),
            Map.entry("Boulevard", "Blvd"), Map.entry("Drive", "Dr"), Map.entry("Lane", "Ln"),
            Map.entry("Court", "Ct"), Map.entry("Place", "Pl"), Map.entry("Parkway", "Pkwy"),
            Map.entry("Highway", "Hwy"), Map.entry("Terrace", "Ter"), Map.entry("Circle", "Cir"),
            Map.entry("Square", "Sq"), Map.entry("Expressway", "Expy"), Map.entry("Turnpike", "Tpke"),
            Map.entry("Trail", "Trl"), Map.entry("Alley", "Aly"));

    private static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("Alabama", "AL"), Map.entry("Alaska", "AK"), Map.entry("Arizona", "AZ"),
            Map.entry("Arkansas", "AR"), Map.entry("California", "CA"), Map.entry("Colorado", "CO"),
            Map.entry("Connecticut", "CT"), Map.entry("Delaware", "DE"), Map.entry("District of Columbia", "DC"),
            Map.entry("Florida", "FL"), Map.entry("Georgia", "GA"), Map.entry("Hawaii", "HI"),
            Map.entry("Idaho", "ID"), Map.entry("Illinois", "IL"), Map.entry("Indiana", "IN"),
            Map.entry("Iowa", "IA"), Map.entry("Kansas", "KS"), Map.entry("Kentucky", "KY"),
            Map.entry("Louisiana", "LA"), Map.entry("Maine", "ME"), Map.entry("Maryland", "MD"),
            Map.entry("Massachusetts", "MA"), Map.entry("Michigan", "MI"), Map.entry("Minnesota", "MN"),
            Map.entry("Mississippi", "MS"), Map.entry("Missouri", "MO"), Map.entry("Montana", "MT"),
            Map.entry("Nebraska", "NE"), Map.entry("Nevada", "NV"), Map.entry("New Hampshire", "NH"),
            Map.entry("New Jersey", "NJ"), Map.entry("New Mexico", "NM"), Map.entry("New York", "NY"),
            Map.entry("North Carolina", "NC"), Map.entry("North Dakota", "ND"), Map.entry("Ohio", "OH"),
            Map.entry("Oklahoma", "OK"), Map.entry("Oregon", "OR"), Map.entry("Pennsylvania", "PA"),
            Map.entry("Rhode Island", "RI"), Map.entry("South Carolina", "SC"), Map.entry("South Dakota", "SD"),
            Map.entry("Tennessee", "TN"), Map.entry("Texas", "TX"), Map.entry("Utah", "UT"),
            Map.entry("Vermont", "VT"), Map.entry("Virginia", "VA"), Map.entry("Washington", "WA"),
            Map.entry("West Virginia", "WV"), Map.entry("Wisconsin", "WI"), Map.entry("Wyoming", "WY"),
            Map.entry("Puerto Rico", "PR"));

    private UsAddress() {
    }

    static String state(String state) {
        return STATES.getOrDefault(state, state);
    }

    /**
     * Abbreviates the suffix and a leading or trailing direction. A direction is kept when it is the
     * street's actual name, as in "North Avenue".
     */
    static String street(String road) {
        String[] words = road.trim().split("\\s+");
        if (words.length < 2) {
            return road;
        }
        int last = words.length - 1;
        if (DIRECTIONS.containsKey(words[last]) && words.length > 2) {
            words[last] = DIRECTIONS.get(words[last]);
            last--;
        }
        words[last] = SUFFIXES.getOrDefault(words[last], words[last]);
        if (DIRECTIONS.containsKey(words[0]) && last > 1) {
            words[0] = DIRECTIONS.get(words[0]);
        }
        return String.join(" ", words);
    }
}
