package fr.wael.reactiveorders.domain;

/** Une règle métier violée ; aucun statut HTTP ni framework ne fuit dans le domaine. */
public class DomainRuleException extends RuntimeException {
    public DomainRuleException(String message) { super(message); }
}
