package com.pandacorp.botanousv2;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import cz.cuni.amis.pogamut.base.communication.worldview.listener.annotation.EventListener;
import cz.cuni.amis.pogamut.base.utils.guice.AgentScoped;
import cz.cuni.amis.pogamut.ut2004.agent.module.utils.TabooSet;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.NavigationState;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.UT2004PathAutoFixer;
import cz.cuni.amis.pogamut.ut2004.bot.impl.UT2004Bot;
import cz.cuni.amis.pogamut.ut2004.bot.impl.UT2004BotModuleController;
import cz.cuni.amis.pogamut.ut2004.communication.messages.ItemType;
import cz.cuni.amis.pogamut.ut2004.communication.messages.UT2004ItemType;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Initialize;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.StopShooting;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.BotDamaged;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.BotKilled;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.Item;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.Player;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.PlayerDamaged;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.PlayerKilled;
import cz.cuni.amis.pogamut.ut2004.utils.UT2004BotRunner;
import cz.cuni.amis.utils.collections.MyCollections;
import cz.cuni.amis.utils.exception.PogamutException;
import cz.cuni.amis.utils.flag.FlagListener;
import java.util.Map;

// Import du pacakge pour la logique floue
import fuzzy4j.flc.ControllerBuilder;
import fuzzy4j.flc.FLC;
import fuzzy4j.flc.InputInstance;
import fuzzy4j.flc.Term;
import static fuzzy4j.flc.Term.term;
import fuzzy4j.flc.Variable;
import static fuzzy4j.flc.Variable.input;
import static fuzzy4j.flc.Variable.output;

@AgentScoped
public class HunterBot extends UT2004BotModuleController<UT2004Bot> {

    // Timer pour définir la transistion search -> idle
    private int timer = 0;
    private final int maxIteration = 40; // Soit 10 secondes

    // Un joueur a été tué par Botanous
    @EventListener(eventClass = PlayerKilled.class)
    public void playerKilled(PlayerKilled event) {
        // Si c'est notre bot 
        if (event.getKiller().equals(info.getId())) {
            log.info("Ennemi tué.");
            hasKilled = true;
        }
        if (enemy == null) {
            return;
        }
        if (enemy.getId().equals(event.getId())) {
            enemy = null;
        }
    }

    // Boolean de gestion des transistions
    private boolean hasKilled = false;
    private boolean losesInterest = true;
    private boolean isStillHurt = false;

    private State currentState = State.IDLE;

    // Représente le joueur que l'on chasse
    protected Player enemy = null;

    // Le dernier joueur que l'on a vu
    protected Player lastEnemy = null;

    // Item que l'on veut
    protected Item item = null;

    // Liste d'items interdit
    protected TabooSet<Item> tabooItems = null;

    // [??]
    private UT2004PathAutoFixer autoFixer;

    // Définition des états possible de Botanous
    private enum State {

        IDLE,
        ATTACK,
        SEARCH,
        HURT,
        DEAD
    }

    // Définition des états possibles pour l'état Hurt
    private enum FuzzyStateHurt {

        RunAwayAndSeekHealth,
        AttackAndSeekHealth,
        Attack
    }

    // Application de la logique floue
    // ==============================================
    private double FuzzyLogicHealth(int currentHealth) {
        // Terms pour la santé
        Term low = term("low", 0, 25, 40);
        Term average = term("average", 25, 40, 60, 75);
        Term high = term("high", 60, 75, 200);
        Variable health = input("health", low, average, high).start(0).end(200);

        // Terms pour l'état de sortie
        Term RunAwayAndSeekHealth = term("RunAwayAndSeekHealth", 0, 0.5, 1);
        Term AttackAndSeekHealth = term("AttackAndSeekHealth", 1, 1.5, 2);
        Term Attack = term("Attack", 2, 2.5, 3);
        Variable fuzzyStateHurt = output("fuzzyStateHurt", RunAwayAndSeekHealth, AttackAndSeekHealth, Attack).start(0).end(3);

        FLC impl = ControllerBuilder.newBuilder()
                .when().var(health).is(low).then().var(fuzzyStateHurt).is(RunAwayAndSeekHealth)
                .when().var(health).is(average).then().var(fuzzyStateHurt).is(AttackAndSeekHealth)
                .when().var(health).is(high).then().var(fuzzyStateHurt).is(Attack)
                .create();

        InputInstance instance = new InputInstance().is(health, currentHealth);
        Map<Variable, Double> crisp = impl.apply(instance);

        return crisp.get(fuzzyStateHurt);
    }

// Fonction de préparation - avant que le bot soit connecté
    @Override
    public void prepareBot(UT2004Bot bot) {
        tabooItems = new TabooSet<Item>(bot);
        autoFixer = new UT2004PathAutoFixer(bot, navigation.getPathExecutor(), fwMap, aStar, navBuilder); // auto-removes wrong navigation links between navpoints

        // listeners        
        navigation.getState().addListener(new FlagListener<NavigationState>() {

            @Override
            public void flagChanged(NavigationState changedValue) {
                switch (changedValue) {
                    case PATH_COMPUTATION_FAILED:
                    case STUCK:
                        if (item != null) {
                            tabooItems.add(item, 10);
                        }
                        reset();
                        break;

                    case TARGET_REACHED:
                        reset();
                        break;
                }
            }
        });

        // DEFINE WEAPON PREFERENCES
        weaponPrefs.addGeneralPref(UT2004ItemType.MINIGUN, false);
        weaponPrefs.addGeneralPref(UT2004ItemType.FLAK_CANNON, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.ROCKET_LAUNCHER, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.LINK_GUN, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.ASSAULT_RIFLE, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.BIO_RIFLE, true);
    }

    // Commande d'inititialisation de Botanous
    @Override
    public Initialize getInitializeCommand() {
        return new Initialize().setName("Botanous");
    }

    // reset de l'état 
    protected void reset() {
        item = null;
        enemy = null;
        lastEnemy = null;
        hasKilled = false;
        losesInterest = true;
        currentState = State.IDLE;
        navigation.stopNavigation();
        itemsToRunAround = null;
        timer = 0;
    }

    // Si Botanous blesse un joueur
    @EventListener(eventClass = PlayerDamaged.class)
    public void playerDamaged(PlayerDamaged event) {
        sayGlobal("Tiiiiiens ! Prends ça :p");
        log.info("Joueur blessé.");
    }

    // Si Botanous est blessé
    @EventListener(eventClass = BotDamaged.class)
    public void botDamaged(BotDamaged event) {
        currentState = State.HURT;
        sayGlobal("Aie :( Ça fait mal ça !!!");
    }

    @Override
    public void logic() {
        // Définition de la machine à état 
        switch (currentState) {
            case IDLE:
                log.info("[IDLE] Entrée");
                stateIdle();
                break;

            case ATTACK:
                log.info("[ATTACK] Entrée");
                stateAttack();
                break;

            case SEARCH:
                log.info("[SEARCH] Entrée");
                stateSearch();
                break;

            case HURT:
                log.info("[HURT] Entrée");
                stateHurt();
                break;

            case DEAD:
                // On rentre directement dans cet état par évenement de Pogamut
                break;

            default:
                break;
        }
    }

    //-------------------------- STATE -------------------------------// 
    // ---- STATE IDLE 
    private void stateIdle() {
        // Transition
        if (isStillHurt) {
            log.info("[IDLE] Sortie");
            currentState = State.HURT;
        } else if (players.canSeeEnemies()) {
            log.info("[IDLE] Sortie");
            currentState = State.ATTACK;
        }
        // Remise à zéro des variables 
        hasKilled = false;
        timer = 0;

        // Action de l'état
        navigate();
    }

    // ---- STATE ATTACK 
    protected boolean runningToPlayer = false;

    private void stateAttack() {
        if (!players.canSeeEnemies() && !hasKilled) {
            log.info("[ATTACK] Sortie");
            currentState = State.SEARCH;
        } else if (hasKilled && !players.canSeeEnemies()) {
            log.info("[ATTACK] Sortie");
            currentState = State.IDLE;
            hasKilled = false;
        }

        // Action de l'état        
        boolean shooting = false;

        double distance = Double.MAX_VALUE;

        // On récupère l'ennemi le plus proche.        
        enemy = players.getNearestVisiblePlayer(players.getVisibleEnemies().values());

        // pew pew pew
        if (enemy != null) {
            distance = info.getLocation().getDistance(enemy.getLocation());
            if (shoot.shoot(weaponPrefs, enemy) != null) {
                lastEnemy = enemy;
                shooting = true;
            }
            // Si le joueur s'éloigne a une certaine distance, mais visible, on le suit
            int decentDistance = Math.round(random.nextFloat() * 800) + 200;
            if (!enemy.isVisible() || !shooting || decentDistance < distance) {
                if (!runningToPlayer) {
                    navigation.navigate(enemy);
                    runningToPlayer = true;
                }
            } else {
                runningToPlayer = false;
                navigation.stopNavigation();
            }
        }
    }

    private void attack() {
        enemy = players.getNearestVisiblePlayer(players.getVisibleEnemies().values());

        int decentDistance = Math.round(random.nextFloat() * 800) + 200;
        double distance = Double.MAX_VALUE;

        if (enemy != null) {
            if ((enemy.isVisible() || decentDistance < distance) && !info.isShooting()) {
                shoot.shoot(weaponPrefs, enemy);
            } else {
                // On arrête de tirer
                if (info.isShooting() || info.isSecondaryShooting()) {
                    getAct().act(new StopShooting());
                }
            }
        }
    }

    // --- STATE SEARCH
    private void stateSearch() {
        losesInterest = timer >= maxIteration;

        // Transistion & Action     
        if (!losesInterest && !hasKilled) {
            timer++;
            log.info("Joueur perdu depuis [" + timer + "]" + " itérations.");

            enemy = players.getNearestVisiblePlayer(players.getVisibleEnemies().values());

            // Si il n'y a personne
            if (enemy == null || !enemy.isVisible()) {
                // On arrête de tirer
                if (info.isShooting() || info.isSecondaryShooting()) {
                    getAct().act(new StopShooting());
                }
                // On cherche le dernier ennemi vu
                navigation.navigate(lastEnemy);
            } else {
                log.info("[SEARCH] Sortie");
                currentState = State.ATTACK;
            }
        } else {
            timer = 0;
            log.info("[SEARCH] Sortie");
            currentState = State.IDLE;
            sayGlobal("Je t'aurais un jour, je t'aurais.");
        }
    }

    // ---- STATE HURT 
    private void stateHurt() {

        // Récupération de la valeur générée par la FuzzyLogic
        double valueState = FuzzyLogicHealth(info.getHealth());

        FuzzyStateHurt currentFuzzyStateHurt = FuzzyStateHurt.Attack;

        isStillHurt = true;
        // Gestion des transistions par la détermination de l'état dans hurt
        log.info(String.valueOf(valueState));
        if (valueState < 1) {
            currentFuzzyStateHurt = FuzzyStateHurt.RunAwayAndSeekHealth;
        } else if (valueState >= 1 && valueState <= 2) {
            currentFuzzyStateHurt = FuzzyStateHurt.AttackAndSeekHealth;
        } else if (valueState > 2) {
            currentFuzzyStateHurt = FuzzyStateHurt.Attack;
            isStillHurt = false;
        }

        // Action de l'état
        switch (currentFuzzyStateHurt) {
            case RunAwayAndSeekHealth:
                SeekHealth(false);
                break;

            case AttackAndSeekHealth:
                SeekHealth(true);
                break;

            case Attack:
                if (players.canSeeEnemies()) {
                    currentState = State.ATTACK;
                } else if (!hasKilled) {
                    currentState = State.SEARCH;
                } else {
                    currentState = State.IDLE;
                }
                break;
        }
    }

    void SeekHealth(boolean shouldAttack) {
        if (shouldAttack) {
            attack();
        } else {
            if (info.isShooting() || info.isSecondaryShooting()) {
                getAct().act(new StopShooting());
            }
        }

        // Seek health
        List<Item> healthItems = new ArrayList<Item>();
        healthItems.addAll(items.getSpawnedItems(UT2004ItemType.HEALTH_PACK).values());

        Item healthItem = MyCollections.getRandom(tabooItems.filter(healthItems));

        if (healthItem == null) {
            if (navigation.isNavigating()) {
                return;
            }
            navigation.navigate(navPoints.getRandomNavPoint());
        } else {
            navigation.navigate(healthItem);
        }
    }

    // -- Pour parler
    private void sayGlobal(String msg) {
        body.getCommunication().sendGlobalTextMessage(msg);
        log.info(msg);
    }

    // Gestion de la navigation de Botanous
    protected List<Item> itemsToRunAround = null;

    protected void navigate() {
        // Si le bot est déjà en train de bouger
        if (navigation.isNavigatingToItem()) {
            return;
        }

        // Liste des items interessants
        List<Item> interesting = new ArrayList<Item>();

        // Parcours des armes 
        for (ItemType itemType : ItemType.Category.WEAPON.getTypes()) {
            // S'il n'a pas rechargé son arme 
            if (!weaponry.hasLoadedWeapon(itemType)) {
                // Ajout de ces armes à la liste des objects interessants
                interesting.addAll(items.getSpawnedItems(itemType).values());
            }
        }

        // Parcours des armures
        for (ItemType itemType : ItemType.Category.ARMOR.getTypes()) {
            interesting.addAll(items.getSpawnedItems(itemType).values());
        }

        // Ajout des items de type adrénaline
        interesting.addAll(items.getSpawnedItems(UT2004ItemType.U_DAMAGE_PACK).values());

        // On choisit un item aléatoire dans la liste des items intéressants
        Item item = MyCollections.getRandom(tabooItems.filter(interesting));

        // Si jamais il n'y a pas d'item interessant
        if (item == null) {
            if (navigation.isNavigating()) {
                return;
            }
            navigation.navigate(navPoints.getRandomNavPoint());
        } else {
            this.item = item;
            navigation.navigate(item);
        }
    }

    // ---- STATE DEAD 
    // Réprésente notre état DEAD, qui est un état furtif
    @Override
    public void botKilled(BotKilled event) {
        // Le bot est mort 
        sayGlobal("J'ai été tué :'(");

        // On repasse en IDLE
        currentState = State.IDLE;

        // En le remettant à zéro
        reset();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    public static void main(String args[]) throws PogamutException {
        new UT2004BotRunner(HunterBot.class, "Hunter").setMain(true).setLogLevel(Level.INFO).startAgents(1);
    }

}
