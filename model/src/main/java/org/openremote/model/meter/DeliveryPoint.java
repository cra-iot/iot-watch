package org.openremote.model.meter;

import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueConstraint;
import org.openremote.model.value.ValueType;

/**
 * The delivery point ("odběrné místo") identifier shared by every meter asset type: the
 * electricity EAN (18 digits, 859182400…) or the gas EIC (16 characters, 27ZG…). One
 * attribute name across commodities so a single asset query finds a delivery point whatever
 * it measures.
 *
 * <p>Operator-provisioned, never decoded — the name is in {@code SameNameDecoder}'s reserved
 * set, so no device payload can overwrite it.
 */
public final class DeliveryPoint {

    public static final String ATTRIBUTE_NAME = "delivery_point_id";

    public static final String LABEL = "Identifikátor odběrného místa (EAN/EIC)";

    /**
     * Electricity EAN: exactly 18 digits. Gas EIC: 16 characters — a two-digit issuing-office
     * number, the object-type letter, 12 free characters and a check character. Codes are
     * stored uppercase and unpadded; anything else is a typo that no equality search would find.
     */
    public static final String PATTERN = "^(\\d{18}|[0-9]{2}[XYZWTAV][A-Z0-9-]{12}[A-Z0-9])$";

    private DeliveryPoint() {
    }

    /**
     * A fresh descriptor instance for one asset type. {@link AttributeDescriptor} is mutable
     * (e.g. {@code updateConstraints()}), so sharing one instance across asset types would let a
     * mutation on one type's descriptor leak into every other type's; each call returns its own
     * instance to keep that impossible. Each meter class must also hold its own static field
     * rather than inherit one — {@code SameNameDecoder} discovers attribute names through
     * {@code getDeclaredFields()}, which does not see fields declared on a superclass.
     */
    public static AttributeDescriptor<String> descriptor() {
        return new AttributeDescriptor<>(ATTRIBUTE_NAME, ValueType.TEXT,
                new MetaItem<>(MetaItemType.LABEL, LABEL),
                new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ))
                .withOptional(true)
                .withConstraints(new ValueConstraint.Pattern(PATTERN));
    }
}
