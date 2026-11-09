/*
 * Cette sonde initialise une fois le replica set puis vérifie son primary.
 * MongoDB n'autorise pas les transactions multi-documents sur un standalone.
 * Une seule écriture transactionnelle peut ainsi enregistrer order + outbox.
 *
 * DNS annoncé aux autres conteneurs : mongo:27017.
 * Depuis l'hôte : mongodb://localhost:27017/reactive_orders
 *                  ?replicaSet=rs0&directConnection=true
 * directConnection évite que le client local tente de résoudre "mongo".
 */
try {
  try {
    rs.status();
  } catch (error) {
    // Seule l'absence d'initialisation autorise rs.initiate : une vraie panne
    // ou une erreur de configuration ne doit pas être maquillée en réinitialisation.
    if (error.code !== 94 && error.codeName !== "NotYetInitialized") {
      throw error;
    }
    rs.initiate({
      _id: "rs0",
      members: [{ _id: 0, host: "mongo:27017" }],
    });
  }
  // L'élection peut prendre quelques secondes après initiate. La sonde suivante
  // réessaiera ; aucun sleep arbitraire ne conditionne le démarrage de l'app.
  quit(db.hello().isWritablePrimary ? 0 : 1);
} catch (error) {
  print(`Replica set non prêt : ${error.message}`);
  quit(1);
}
