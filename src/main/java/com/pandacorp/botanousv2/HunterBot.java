package com.pandacorp.botanousv2;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import cz.cuni.amis.introspection.java.JProp;
import cz.cuni.amis.pogamut.base.agent.navigation.IPathExecutorState;
import cz.cuni.amis.pogamut.base.communication.worldview.listener.annotation.EventListener;
import cz.cuni.amis.pogamut.base.utils.Pogamut;
import cz.cuni.amis.pogamut.base.utils.guice.AgentScoped;
import cz.cuni.amis.pogamut.base.utils.math.DistanceUtils;
import cz.cuni.amis.pogamut.base3d.worldview.object.ILocated;
import cz.cuni.amis.pogamut.ut2004.agent.module.utils.TabooSet;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.NavigationState;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.UT2004PathAutoFixer;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.stuckdetector.UT2004DistanceStuckDetector;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.stuckdetector.UT2004PositionStuckDetector;
import cz.cuni.amis.pogamut.ut2004.agent.navigation.stuckdetector.UT2004TimeStuckDetector;
import cz.cuni.amis.pogamut.ut2004.bot.impl.UT2004Bot;
import cz.cuni.amis.pogamut.ut2004.bot.impl.UT2004BotModuleController;
import cz.cuni.amis.pogamut.ut2004.communication.messages.ItemType;
import cz.cuni.amis.pogamut.ut2004.communication.messages.UT2004ItemType;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Initialize;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Move;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Rotate;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Stop;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.StopShooting;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.BotDamaged;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.BotKilled;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.Item;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.NavPoint;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.Player;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.PlayerDamaged;
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.PlayerKilled;
import cz.cuni.amis.pogamut.ut2004.utils.UT2004BotRunner;
import cz.cuni.amis.utils.collections.MyCollections;
import cz.cuni.amis.utils.exception.PogamutException;
import cz.cuni.amis.utils.flag.FlagListener;
import cz.cuni.amis.pogamut.base.utils.math.DistanceUtils;



@AgentScoped
public class HunterBot extends UT2004BotModuleController<UT2004Bot> 
{

    // Timer pour définir la transistion search -> idle
    private int timer = 0;
    private int maxIteration = 40; // Soit 10 secondes
    // Minimun de santé pour aller chercher de quoi se soigner
    private int minHealth = 40;
    
   // Est-ce que je dois attaquer ?
    @JProp
    public boolean shouldEngage = true;
  
    
    // Est-ce que je dois te poursuivre ? 
    @JProp
    public boolean shouldPursue = true;
  
    
    // Est-ce que je dois réarmer ?? 
    @JProp
    public boolean shouldRearm = true;
    
    
    // Est-ce que je dois récupérer de la santé ? 
    @JProp
    public boolean shouldCollectHealth = true;
   
    
    // A partir de quand je dois aller chercher de la santé
    @JProp
    public int healthLevel = 75;
  
    // Combien de personnes j'ai tué ? 
    @JProp
    public int frags = 0;
    
    // Combien de fois je suis mort ?
    @JProp
    public int deaths = 0;  
    
    // Un joueur a été tué par Botanous
    @EventListener(eventClass = PlayerKilled.class)
    public void playerKilled(PlayerKilled event) 
    {
        // Si c'est notre bot 
        if (event.getKiller().equals(info.getId())) {
            ++frags;
            hasKilled = true;            
        }
        if (enemy == null) 
        {
            return;
        }
        if (enemy.getId().equals(event.getId())) 
        {
            enemy = null;
        }
    }
   
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
    
    // Devait représenter notre timer
    //private static int instanceCount = 0;
    
    // Définition des états possible de Botanous
    private enum State
    {
        IDLE,
        ATTACK,
        SEARCH,
        HURT,
        DEAD
    }
    
    
    private boolean isHurt = false;
    private boolean isDead = false;
    private boolean hasKilled = false;
    private boolean losesInterest = false;
    private State currentState = State.IDLE;
    

// Fonction de préparation - avant que le bot soit connecté
    @Override
    public void prepareBot(UT2004Bot bot) 
    {
        // [??]
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

        // DEFINE WEAPON PREFERENCES [IDEE] Peut-être utiliser quelque chose ici
        weaponPrefs.addGeneralPref(UT2004ItemType.LIGHTNING_GUN, true);                
        weaponPrefs.addGeneralPref(UT2004ItemType.SHOCK_RIFLE, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.MINIGUN, false);
        weaponPrefs.addGeneralPref(UT2004ItemType.FLAK_CANNON, true);        
        weaponPrefs.addGeneralPref(UT2004ItemType.ROCKET_LAUNCHER, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.LINK_GUN, true);
        weaponPrefs.addGeneralPref(UT2004ItemType.ASSAULT_RIFLE, true);        
        weaponPrefs.addGeneralPref(UT2004ItemType.BIO_RIFLE, true);
    }   
    
    // Commande d'inititialisation de Botanous
    @Override
    public Initialize getInitializeCommand() 
    {        
        return new Initialize().setName("Botanous");
    }   
    
    // reset de l'état 
    protected void reset() {
    	item = null;
        enemy = null;
        isDead = false;
        isHurt = false;
        hasKilled = false;
        currentState = State.IDLE;
        navigation.stopNavigation();
        itemsToRunAround = null;
        timer = 0;
    }
    
    // Si Botanous blesse un joueur
    @EventListener(eventClass=PlayerDamaged.class)
    public void playerDamaged(PlayerDamaged event) 
    {
    	log.info("I have just hurt other bot for: " + event.getDamageType() + "[" + event.getDamage() + "]");
    }
    
    // Si Botanous est blessé
    @EventListener(eventClass=BotDamaged.class)
    public void botDamaged(BotDamaged event) 
    {       
        isHurt = true;        
        sayGlobal("*Tchiiiip*");        
    }
    
    // Appelé 4 fois par seconde (visionTime variable, changeable dans GameBot ini file in UT2004/System folder)
    @Override
    public void logic() { 

        // Définition de la machine à état 
        switch(currentState)
        {
            case IDLE:
                stateIdle();
                break;
                
            case ATTACK:
                stateAttack();
                break; 
                
            case SEARCH :
                stateSearch();               
                break;
                
            case HURT:
                stateHurt();
                break;
                
            case DEAD :
                stateDead();
                break;
                
            default:
                break;                
        }
        
        
        /*
        
        // 1) do you see enemy? 	-> go to PURSUE (start shooting / hunt the enemy)
        if (shouldEngage && players.canSeeEnemies() && weaponry.hasLoadedWeapon()) {
            stateEngage();
            return;
        }

        // 2) are you shooting? 	-> stop shooting, you've lost your target
        if (info.isShooting() || info.isSecondaryShooting()) {
            getAct().act(new StopShooting());
        }

        // 3) are you being shot? 	-> go to HIT (turn around - try to find your enemy)
        if (senses.isBeingDamaged()) {
            this.stateHit();
            return;
        }

        // 4) have you got enemy to pursue? -> go to the last position of enemy
        if (enemy != null && shouldPursue && weaponry.hasLoadedWeapon()) {  // !enemy.isVisible() because of 2)
            this.statePursue();
            return;
        }

        // 5) are you hurt?			-> get yourself some medKit
        if (shouldCollectHealth && info.getHealth() < healthLevel) {
            this.stateMedKit();
            return;
        }

        // 6) if nothing ... run around items
        stateRunAroundItems();
        */
    }
    
    //-------------------------- STATE -------------------------------// 
    
    // ---- STATE IDDLE 
    private void stateIdle()
    {
        // Transition
        if(players.canSeeEnemies())
        {
            currentState = State.ATTACK;
        }
        else if (isHurt)
        {
            currentState = State.HURT;
        }
        else if (isDead)
        {
            currentState = State.DEAD;
        }        
         // Remise à zéro des variables 
        isHurt = false;
        isDead = false; 
        hasKilled = false;
        timer = 0;        
        
        // Action de l'état
        navigate();
        sayGlobal("LA LA LA LA !! JE IDLE !!!!");
    }
    
    // ---- STATE ATTACK 
    private void stateAttack()
    {
        // Gestion des transistions
        if(isHurt && !isDead && info.getHealth() < minHealth)
        {
            currentState = State.HURT;
        }
        else if (!players.canSeeEnemies() && !hasKilled)
        {
            currentState = State.SEARCH;
        }
        else if(isDead)
        {
            currentState = State.DEAD;
        }
        else if (hasKilled && !players.canSeeEnemies())
        {
            currentState = State.IDLE;
        }      
        
        // Action de l'état
        // boolean pour savoir si on tire.
        boolean shooting = false;
        
        // La distance entre le bot et le joueur
        double distance = Double.MAX_VALUE;
        
        pursueCount = 0;

        // On récupère l'ennemi le plus proche.
        lastEnemy = enemy = players.getNearestVisiblePlayer(players.getVisibleEnemies().values());
        
        // pew pew pew
        if(enemy != null)
        {
            distance = info.getLocation().getDistance(enemy.getLocation());
            if (shoot.shoot(weaponPrefs, enemy) != null) 
            {
                sayGlobal("PEW PEW PEW");
                shooting = true;
            }

            // 3) if enemy is far or not visible - run to him
            int decentDistance = Math.round(random.nextFloat() * 800) + 200;
            
            if (!enemy.isVisible() || !shooting || decentDistance < distance) 
            {
                if (!runningToPlayer) 
                {
                    navigation.navigate(enemy);
                    runningToPlayer = true;
                }
            } 
            else 
            {
                runningToPlayer = false;
                navigation.stopNavigation();
            }
        }
        
        // Rechargement intelligent
        
        
                
    }
    
    // --- STATE SEARCH
    private void stateSearch()
    {
        losesInterest = timer >= maxIteration;
                
        // Transistion & Action     
        if(!losesInterest)
        {
            timer++;
            if(enemy != null)
            {
                sayGlobal("[" + timer + "]" + " Petit, Petit, Petit... Viens vois papa " + enemy.getName());
            }
            else sayGlobal("[" + timer + "]" + " Ohé ? ");
          
            // Si on a un enemy plus proche
            if (enemy == null || !enemy.isVisible()) 
            {
                // On prends un nouvel enemy
                enemy = players.getNearestVisiblePlayer(players.getVisibleEnemies().values());
                if (enemy != null) 
                {
                   currentState = State.ATTACK;
                }                
            }
        }
        else if (hasKilled || losesInterest)
        {
           timer = 0;
           currentState = State.IDLE; 
           sayGlobal("Je t'aurais un jour, je t'aurais. Connard.");
        } 
    }
    
    // ---- STATE HURT 
    private void stateHurt()
    {
        // Transistion
        if(info.getHealth() > 51)
        {
            currentState = State.ATTACK;
        }
        else if (isDead)
        {
            currentState = State.DEAD;
        }
        else if(!players.canSeeEnemies())
        {
            currentState = State.SEARCH;
        }
        // Action de l'état
        sayGlobal("POUCE");
    }
    
    // ---- STATE DEAD 
    private void stateDead()
    {
        // Transition 
        currentState = State.IDLE;
        // Action de l'état 
        sayGlobal("AAYYYY CARAMBA!!!! AY AY AY!!!! ");
        reset();
    }

    // -- Pour blabla :)
    private void sayGlobal(String msg) {
    	// Simple way to send msg into the UT2004 chat
    	body.getCommunication().sendGlobalTextMessage(msg);
    	// And user log as well
    	log.info(msg);
    }
    
    // Gestion de la navigation de Botanous
    protected List<Item> itemsToRunAround = null;

    protected void navigate()
    {
        // Si le bot est déjà en train de bouger
        if (navigation.isNavigatingToItem()) return;

        // Liste des items interessants
        List<Item> interesting = new ArrayList<Item>();

        // Parcours des armes 
        for (ItemType itemType : ItemType.Category.WEAPON.getTypes()) 
        {
                // S'il n'a pas rechargé son arme 
                if (!weaponry.hasLoadedWeapon(itemType))
                {
                    // Ajout de ces armes à la liste des objects interessants
                    interesting.addAll(items.getSpawnedItems(itemType).values());
                }
        }

        // Parcours des armures
        for (ItemType itemType : ItemType.Category.ARMOR.getTypes()) 
        {
             interesting.addAll(items.getSpawnedItems(itemType).values());
        }            

        // Ajout des items de type adrénaline
        interesting.addAll(items.getSpawnedItems(UT2004ItemType.U_DAMAGE_PACK).values());


        // Parcours des objets de santé si blessé
        if (info.getHealth() < 100) 
        {                
             interesting.addAll(items.getSpawnedItems(UT2004ItemType.HEALTH_PACK).values());
        }

        // On choisit un item aléatoire dans la liste des items intéressants
        Item item = MyCollections.getRandom(tabooItems.filter(interesting));
        
        // Si jamais il n'y a pas d'item interessant
        if (item == null) 
        {                
                log.info("[IDLE] Pas d'objet intéressant trouvé");
                if (navigation.isNavigating()) return;
                
                // On choisit un point aléatoire
                navigation.navigate(navPoints.getRandomNavPoint());
        } 
        else 
        {
                this.item = item;
                log.info("Je vais prendre --> " + item.getType().getName());
                navigation.navigate(item);        	
        }        
    }    
    
    // ---- Fin de la gestion de Botanous personnelle
    
    
    //////////////////
    // STATE ENGAGE //
    //////////////////
    protected boolean runningToPlayer = false;

    /**
     * Fired when bot see any enemy. <ol> <li> if enemy that was attacked last
     * time is not visible than choose new enemy <li> if enemy is reachable and the bot is far - run to him
     * <li> otherwise - stand still (kind a silly, right? :-)
     * </ol>
     */
    protected void stateEngage() {
        //log.info("Decision is: ENGAGE");
        //config.setName("Hunter [ENGAGE]");

        boolean shooting = false;
        double distance = Double.MAX_VALUE;
        pursueCount = 0;

        // 1) pick new enemy if the old one has been lost
        if (enemy == null || !enemy.isVisible()) {
            // pick new enemy
            enemy = players.getNearestVisiblePlayer(players.getVisibleEnemies().values());
            if (enemy == null) {
                log.info("Can't see any enemies... ???");
                return;
            }
        }

        // 2) stop shooting if enemy is not visible
        if (!enemy.isVisible()) {
	        if (info.isShooting() || info.isSecondaryShooting()) {
                // stop shooting
                getAct().act(new StopShooting());
            }
            runningToPlayer = false;
        } else {
        	// 2) or shoot on enemy if it is visible
	        distance = info.getLocation().getDistance(enemy.getLocation());
	        if (shoot.shoot(weaponPrefs, enemy) != null) {
	            log.info("Shooting at enemy!!!");
	            shooting = true;
	        }
        }

        // 3) if enemy is far or not visible - run to him
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
        
        item = null;
    }

    ///////////////
    // STATE HIT //
    ///////////////
    protected void stateHit() {
        //log.info("Decision is: HIT");
        bot.getBotName().setInfo("HIT");
        if (navigation.isNavigating()) {
        	navigation.stopNavigation();
        	item = null;
        }
        getAct().act(new Rotate().setAmount(32000));
    }

    //////////////////
    // STATE PURSUE //
    //////////////////
    /**
     * State pursue is for pursuing enemy who was for example lost behind a
     * corner. How it works?: <ol> <li> initialize properties <li> obtain path
     * to the enemy <li> follow the path - if it reaches the end - set lastEnemy
     * to null - bot would have seen him before or lost him once for all </ol>
     */
    protected void statePursue() {
        //log.info("Decision is: PURSUE");
        ++pursueCount;
        if (pursueCount > 30) {
            reset();
        }
        if (enemy != null) {
        	bot.getBotName().setInfo("PURSUE");
        	navigation.navigate(enemy);
        	item = null;
        } else {
        	reset();
        }
    }
    protected int pursueCount = 0;

    //////////////////
    // STATE MEDKIT //
    //////////////////
    protected void stateMedKit() {
        //log.info("Decision is: MEDKIT");
        Item item = items.getPathNearestSpawnedItem(ItemType.Category.HEALTH);
        if (item == null) {
        	log.warning("NO HEALTH ITEM TO RUN TO => ITEMS");
        	stateRunAroundItems();
        } else {
        	bot.getBotName().setInfo("MEDKIT");
        	navigation.navigate(item);
        	this.item = item;
        }
    }

    ////////////////////////////
    // STATE RUN AROUND ITEMS //
    ////////////////////////////
   

    ////////////////
    // BOT KILLED //
    ////////////////
    @Override
    public void botKilled(BotKilled event) {
    	isDead = true;
        //reset();
    }

    ///////////////////////////////////
    public static void main(String args[]) throws PogamutException {
        // starts 3 Hunters at once
        // note that this is the most easy way to get a bunch of (the same) bots running at the same time        
    	new UT2004BotRunner(HunterBot.class, "Hunter").setMain(true).setLogLevel(Level.INFO).startAgents(1);
    }
}
