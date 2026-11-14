package com.rmm.adapter.tck;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/**
 * The rule for the client identity of the second binding of case 1: it
 * names another binding than the identity of the first.
 */
final class SecondBindingIdentity {

    private SecondBindingIdentity() {
    }

    /**
     * The certificate of the second binding of case 1 names another binding
     * than that of the first; otherwise both would get the same
     * {@code bindingId}, and cross-talk through it would stay unseen. A value
     * that is no usable PEM is left to the context, which fails with its own
     * message when it is built.
     */
    static void requireAnotherBinding(String ofTheFirst, Optional<String> ofTheSecond) {
        String hint = " - override secondBindingClientIdentity() with the identity of another connection, whose"
                + " certificate names another binding (URI:" + TckAdapterContext.BINDING_URN_PREFIX
                + "<bindingId>), as the host gives every connection its own";
        if (ofTheSecond.isEmpty()) {
            throw new TckViolation("secondBindingConfiguration() has no client identity although the adapter's"
                    + " connections carry one" + hint);
        }
        Optional<String> first;
        Optional<String> second;
        try {
            first = bindingOf(ofTheFirst);
            second = bindingOf(ofTheSecond.get());
        } catch (IllegalArgumentException unusable) {
            return;
        }
        if (ofTheFirst.equals(ofTheSecond.get()) || first.isPresent() && first.equals(second)) {
            throw new TckViolation("Both bindings of case 1 carry the same client identity or name the same binding <"
                    + first.orElse("<none>") + ">: a service that keeps its data per X-RMM-Binding would see one"
                    + " binding twice, and case 1 could not find cross-talk" + hint);
        }
    }

    /** The binding named by the first certificate of a PEM; empty if it names none. */
    private static Optional<String> bindingOf(String pem) {
        List<X509Certificate> certificates = TckPem.parse(pem, "The client identity")
                .certificates();
        return certificates.isEmpty() ? Optional.empty()
                : TckClientIdentity.bindingNamedBy(certificates.get(0));
    }
}
