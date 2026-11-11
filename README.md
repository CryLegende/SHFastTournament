<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHFastTournament

*Mini-tournois automatiques (minage, pêche, combat…) à intervalle aléatoire, avec récompenses du top 3.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Mini-tournois automatiques (minage, pêche, combat…) à intervalle aléatoire, avec récompenses du top 3.

Utilisé sur : **Opale**.

Avec `SHNetwork`, un seul tournoi tourne pour tous les serveurs Opale (voir « Mode réseau »). Sans lui, le plugin se comporte comme sur un serveur seul : chaque serveur lance ses tournois, compte les points en mémoire et garde les victoires dans `wins.yml`.

## Dépendances

- Optionnelles : `PlaceholderAPI`, `SHLogs`, `SHNetwork`

## Logs

Chaque récompense donnée en fin de tournoi est écrite dans SHLogs, dans le fichier `recompenses` : une ligne par joueur, avec le tournoi, la place et la récompense. Le fichier, le texte de la ligne et le texte de chaque récompense se règlent dans `settings.log`. Sans SHLogs, la ligne est écrite dans la console.

## Mode réseau (SHNetwork)

Quand `SHNetwork` est installé et que `reseau.actif` vaut `true`, tous les serveurs partagent le même tournoi. Les tournois comptent l'activité dans tous les mondes sauf ceux de `worlds-blacklist` (`spawn`, `arene`) : ils se jouent sur les îles, donc sur les deux serveurs.

- **Même tournoi, même heure.** Le tournoi ouvert est écrit dans la table `fasttournament_runs` avec son heure de fin. Le serveur qui le lance publie `start` sur le canal `fasttournament` : tous les serveurs, le leur compris, affichent le même message, la même boss bar et le même compte à rebours. Un joueur qui se connecte sur l'un ou l'autre reçoit le message « tournoi en cours ». Un serveur qui démarre en pleine partie (ou qui a perdu un message) retrouve le tournoi dans la base, sans annonce de début.
- **Points additionnés.** Chaque serveur compte les actions de ses joueurs comme avant et envoie ses nouveaux points à la base toutes les `reseau.synchro-ms` millisecondes, par `score = score + ?` : les serveurs s'additionnent, dans n'importe quel ordre. Il relit ensuite les points de tout le monde pour la boss bar (rang et participants du réseau entier). Les points d'un joueur sont toujours dans une seule couche à la fois (en attente, en cours d'envoi, ou déjà relevés), donc son total ne baisse ni ne double pendant une synchronisation.
- **Changement de serveur en cours de tournoi.** Les points marqués sur l'ancien serveur partent à son prochain tour, ceux du nouveau s'y ajoutent : rien n'est perdu ni compté deux fois. Le départ du joueur n'attend pas la base. Seul un arrêt brutal (`kill -9`) de l'ancien serveur peut perdre les points marqués depuis son dernier envoi, soit `synchro-ms` au plus.
- **L'heure de fin est la même partout.** Passé cette heure, plus aucun serveur ne compte de point. Le meneur laisse `reseau.delai-conclusion-secondes` secondes aux serveurs pour envoyer leurs derniers points, puis conclut.
- **Meneur.** La tâche de meneur `shfasttournament` n'appartient qu'à un serveur à la fois. Lui seul tire les tournois automatiques, avec les mêmes règles qu'un serveur seul : `minimum-players` compte les joueurs de tout le réseau (`online()` de SHNetwork), `interval` est décompté par lui seul, et le tournoi est tiré au hasard parmi ceux qui sont `enabled`. Il conclut aussi chaque tournoi.
- **Conclusion.** En quatre temps, chacun sans danger s'il est rejoué après une panne :
  1. le meneur réserve la fin (`RUNNING` -> `ENDING`, un seul appelant gagne) ;
  2. il écrit le classement complet dans `fasttournament_results`, une seule fois, avec la victoire du premier dans `fasttournament_wins` (même transaction : la victoire n'est comptée que si la ligne vient d'être écrite) ;
  3. il publie `end` avec le classement : chaque serveur annonce la fin à ses joueurs (mêmes textes qu'un serveur seul), message personnel des participants récompensés, « pas participé » aux autres ;
  4. il réserve la récompense de chaque joueur (`paid` de 0 à 1, un seul appelant gagne) puis envoie ses commandes par `runFor` de SHNetwork : exécutées une seule fois, sur le serveur où se trouve le joueur, ou à sa prochaine connexion s'il est parti. La ligne SHLogs (`recompenses`) est écrite sur le meneur.
- **Si le meneur tombe.** Un autre serveur devient meneur en quelques secondes. Il reprend les tournois `RUNNING` (il les conclut à leur heure de fin) et `ENDING` (il termine ce qui ne l'est pas : classement déjà écrit, récompenses non réservées). Il ne lance jamais de second planificateur : l'ancien meneur arrête le sien en perdant la tâche, et la base refuse un deuxième tournoi ouvert. Le délai avant le prochain tournoi automatique repart à zéro. Un tournoi sans conclusion plus de `reseau.abandon-apres-minutes` minutes après son heure de fin (aucun meneur pendant tout ce temps) est abandonné sans récompense.
- **Arrêt d'un serveur.** Il envoie ses points en attente et ne touche pas au tournoi : il continue sur les autres serveurs, et le serveur le retrouve en redémarrant s'il n'est pas terminé. Un redémarrage de tous les serveurs en plein tournoi ne l'interrompt donc pas.
- **Commandes.** `/shfasttournament start`, `stop`, `custom` et `status` marchent depuis n'importe quel serveur. `start` et `stop` s'appuient sur la base (un seul tournoi ouvert, une seule conclusion), donc elles marchent même sans meneur ; `start ... force` arrête le tournoi ouvert puis en lance un autre. `custom <id> <joueur>` ajoute le point à un joueur connecté sur ce serveur ou sur un autre (`SHCrates` l'appelle pour `BOX` depuis n'importe quel serveur ; `SHMiniKOTH` et `SHEasterBunny` sont sur Opale-1).
- **Messages perdus.** Les serveurs relisent la base à chaque synchronisation : un `stop` perdu est rattrapé, un `start` perdu l'est dans les `reseau.veille-secondes` secondes. Un `end` perdu laisse la boss bar à 00:00 jusqu'à 30 secondes après la conclusion prévue, puis le serveur ferme son tournoi sans classement (les récompenses sont données quand même).

La section `tournaments` et les récompenses de `settings` doivent être les mêmes sur tous les serveurs (le meneur donne les récompenses de sa config ; les autres serveurs comptent selon la leur). Les horloges des serveurs doivent être synchronisées (même machine ou NTP) : l'heure de fin est une heure absolue.

### Victoires

Avec SHNetwork, les victoires sont dans la table `fasttournament_wins (uuid, name, wins)`, incrémentées par `wins = wins + 1` dans la transaction du classement. `%shfasttournament_wins%` lit un cache de `reseau.cache-victoires-secondes` secondes, rempli à la connexion du joueur : il ne fait jamais attendre le serveur. `wins.yml` n'est plus écrit dans ce mode. `/shfasttournament import` en reprend les totaux dans la base (une fois, sur un serveur) : un joueur qui a déjà une ligne n'est pas modifié, donc la commande peut être relancée sans rien compter deux fois.

### Différences avec un serveur seul

- Les récompenses d'un joueur hors ligne sont données à sa prochaine connexion (`runFor`) au lieu d'être exécutées tout de suite sans lui.
- L'annonce de fin arrive `delai-conclusion-secondes` secondes après l'heure de fin.
- Une récompense est donnée au plus une fois : si le meneur tombe entre la réservation de la récompense d'un joueur et l'envoi de ses commandes, ce joueur ne la reçoit pas (fenêtre de quelques millisecondes).
- Les blocs posés pendant un tournoi de casse (`BLOCK_BREAK`) et les positions déjà comptées en `BLOCK_PLACE` sont retenus par serveur : une île n'a qu'un serveur hôte, donc ces blocs ne changent jamais de serveur.

### Installation

1. Base : `mysql -u root < sql/schema.sql` (crée les quatre tables dans `bo_net` et l'utilisateur `shfasttournament`). Remplacer `CHANGE_ME` par le mot de passe.
2. `plugins/SHFastTournament/config.yml`, section `reseau` : `url` et `mot-de-passe`. La même base et la même config sur tous les serveurs.
3. Poser le jar sur chaque serveur, avec SHNetwork. Le jar charge HikariCP et le pilote MariaDB par `libraries:` (le serveur ne les embarque pas) ; sans SHNetwork, ils ne sont pas utilisés.
4. Une fois, sur un serveur : `/shfasttournament import`.

Au démarrage, la console indique `SHNetwork détecté : un seul tournoi pour tout le réseau`. `/shnetwork info` donne le serveur meneur de `shfasttournament`.

### Essayer rapidement

- `/shfasttournament start <id>` sur n'importe quel serveur lance le tournoi partout, sans tenir compte de `minimum-players`. `/shfasttournament status` donne le même résultat sur les deux serveurs.
- `/shfasttournament custom <id> <joueur>` sur un tournoi `CUSTOM` ajoute un point au joueur, où qu'il soit.
- Arrêter le serveur meneur : l'autre reprend la tâche, conclut le tournoi à son heure de fin et recommence à décompter `interval`.

## Commandes

Alias de `/shfasttournament` : `/tournament` (utilisé par les caisses et le KOTH, qui appellent aussi `shfasttournament:tournament custom ...`). Sans argument, la commande affiche les sous-commandes que l'expéditeur a le droit d'utiliser ; elle marche depuis la console.

| Commande | Description | Permission |
|---|---|---|
| `/shfasttournament start <id> [force]` | Démarre un tournoi (`force` arrête celui en cours). | `shfasttournament.admin.start` |
| `/shfasttournament stop` | Arrête le tournoi en cours. | `shfasttournament.admin.stop` |
| `/shfasttournament info` | Liste les tournois activés. | `shfasttournament.admin.info` |
| `/shfasttournament status` | Statut du tournoi en cours. | `shfasttournament.admin.status` |
| `/shfasttournament custom <id> <joueur>` | Ajoute un point à un joueur dans un tournoi `CUSTOM`. | `shfasttournament.admin.custom` |
| `/shfasttournament import` | Avec SHNetwork : reprend les victoires de `wins.yml` dans la base du réseau. | `shfasttournament.admin.import` |
| `/shfasttournament reload` | Relit `config.yml` et `messages.yml` (réglages, récompenses, tournois). Un tournoi en cours finit avec ses anciennes définitions ; si un fichier est invalide, l'ancienne configuration reste active ; `wins.yml` et la base ne sont pas touchés ; la section `reseau` n'est lue qu'au démarrage. | `shfasttournament.admin.reload` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shfasttournament.admin` | Toutes les commandes d'administration. | `op` |
| `shfasttournament.admin.<start\|stop\|info\|status\|custom\|import\|reload>` | Une permission par sous-commande. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHFastTournament/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

Les tables du mode réseau sont décrites dans <a href="sql/schema.sql">sql/schema.sql</a>.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
