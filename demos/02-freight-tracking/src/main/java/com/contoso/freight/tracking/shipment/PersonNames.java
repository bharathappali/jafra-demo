package com.contoso.freight.tracking.shipment;

final class PersonNames {
    private PersonNames() {
    }

    static String initialOnly(String name) {
        int split = name.lastIndexOf(' ');
        if (split < 0 || split + 1 >= name.length()) {
            return name;
        }
        return name.substring(0, split + 1) + name.charAt(split + 1) + '.';
    }
}
