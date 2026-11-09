package fr.wael.reactiveorders.application;

/** Erreurs applicatives : l'adaptateur HTTP choisit leur représentation, jamais les ports. */
public final class Errors {
    private Errors() {}
    public static class NotFound extends RuntimeException { public NotFound(String message) { super(message); } }
    public static class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }
    public static class DuplicateOrder extends Conflict { public DuplicateOrder() { super("Cette commande existe déjà."); } }
    public static class CatalogUnavailable extends RuntimeException {
        public CatalogUnavailable(Throwable cause) { super("Le catalogue est temporairement indisponible.", cause); }
    }
    public static class LostLease extends RuntimeException { public LostLease() { super("Le lease de publication a expiré."); } }
}
