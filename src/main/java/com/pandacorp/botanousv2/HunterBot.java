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
  
    // Un joueur a été tué par Botanous
    @EventListener(eventClass = PlayerKilled.class)
    public void playerKilled(PlayerKilled event) 
    {
        // Si c'est notre bot 
        if (event.getKiller().equals(info.getId())) 
        {
            log.info("Ennemi tué.");
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
    private boolean hasKilled = false;
    private boolean losesInterest = true;
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
            public void flagChanged(NavigationState changedValue) 
            {
                switch (changedValue) 
                {
                    case PATH_COMPUTATION_FAILED:
                    case STUCK:
                        if (item != null) 
                        {
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
    protected void reset() 
    {
    	item = null;
        enemy = null; 
        lastEnemy = null;
        isHurt = false;
        hasKilled = false;
        losesInterest = true;
        currentState = State.IDLE;
        navigation.stopNavigation();
        itemsToRunAround = null;
        timer = 0;
    }
    
    // Si Botanous blesse un joueur
    @EventListener(eventClass=PlayerDamaged.class)
    public void playerDamaged(PlayerDamaged event) 
    {             
    	log.info("Joueur blessé.");
    }
    
    // Si Botanous est blessé
    @EventListener(eventClass=BotDamaged.class)
    public void botDamaged(BotDamaged event) 
    {       
        isHurt = true;        
        //sayGlobal("*Tchiiiip);
        sayGlobal("Je suis blessé. Ma santé : " + info.getHealth());
    }
    
    // Appelé 4 fois par seconde (visionTime variable, changeable dans GameBot ini file in UT2004/System folder)
    @Override
    public void logic() 
    {
        // Définition de la machine à état 
        switch(currentState)
        {
            case IDLE:
                log.info("[IDLE] Entrée");
                stateIdle();
                break;
                
            case ATTACK:
                log.info("[ATTACK] Entrée");
                stateAttack();
                break; 
                
            case SEARCH :
                log.info("[SEARCH] Entrée");
                stateSearch();               
                break;
                
            case HURT:
                log.info("[HURT] Entrée");
                stateHurt();
                break;
                
            case DEAD :
                //stateDead();
                // On rentre directement dans cet état par évenement de Pogamut
                break;
                
            default:
                break;                
        }
    }
    
    //-------------------------- STATE -------------------------------// 
    
    // ---- STATE IDLE 
    private void stateIdle()
    {
        // Transition
        if(players.canSeeEnemies())
        {
            log.info("[IDLE] Sortie");
            currentState = State.ATTACK;
        }
        else if (isHurt)
        {
            log.info("[IDLE] Sortie");
            currentState = State.HURT;
        }       
        
         // Remise à zéro des variables 
        isHurt = false;       
        hasKilled = false;        
        timer = 0;               
        
        // Action de l'état
        navigate();
        //sayGlobal("LA LA LA LA !! JE IDLE !!!!");
    }
    
    
    // ---- STATE ATTACK 
    protected boolean runningToPlayer = false;
    
    private void stateAttack()
    {        
        // Gestion des transistions
        if(isHurt && info.getHealth() < minHealth)
        {
             log.info("[ATTACK] Sortie");   
            currentState = State.HURT;
        }
        else if (!players.canSeeEnemies() && !hasKilled)
        {
            log.info("[ATTACK] Sortie");   
            currentState = State.SEARCH;
        }       
        else if (hasKilled && !players.canSeeEnemies())
        {
            log.info("[ATTACK] Sortie");   
            currentState = State.IDLE;
            hasKilled = false;
        } 
        
        // Action de l'état        
       boolean shooting = false;
        
        // La distance entre le bot et le joueur
        double distance = Double.MAX_VALUE;
        
        // On récupère l'ennemi le plus proche.        
       enemy = players.getNearestVisiblePlayer(players.getVisibleEnemies().values());
        
        // pew pew pew
        if(enemy != null)
        {
            distance = info.getLocation().getDistance(enemy.getLocation());
            if (shoot.shoot(weaponPrefs, enemy) != null) 
            {
                //sayGlobal("PEW PEW PEW");
                lastEnemy = enemy;
                shooting = true;
            }
            // Si le joueur s'éloigne a une certaine distance, mais visible, on le suit
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
        if(!losesInterest && !hasKilled)
        {
            timer++;          
            log.info("[" + timer + "]" + " Ohé ? ");         
            
            enemy = players.getNearestVisiblePlayer(players.getVisibleEnemies().values());
            
            // Si il n'y a personne
            if (enemy == null || !enemy.isVisible()) 
            {
                // On arrête de tirer
                if (info.isShooting() || info.isSecondaryShooting()) 
                {                    
                    getAct().act(new StopShooting());
                }               
                
                 // On cherche le dernier ennemi vu
                 navigation.navigate(lastEnemy);
            }
            else
            {
                log.info("[SEARCH] Sortie");   
                currentState = State.ATTACK;
            }
        }        
        else 
        {
           timer = 0;
           log.info("[SEARCH] Sortie");   
           currentState = State.IDLE; 
           log.info("Je t'aurais un jour, je t'aurais. Connard.");
        }
    }
    
    // ---- STATE HURT 
    private void stateHurt()
    {
        boolean isEnoughHeal = info.getHealth() > minHealth;
        
        // Transistion
        if(isEnoughHeal && players.canSeeEnemies())
        {
            log.info("[HURT] Sortie"); 
            currentState = State.ATTACK;
        }       
        else if(lastEnemy != null && !hasKilled && isEnoughHeal)
        {
            log.info("[HURT] Sortie"); 
            currentState = State.SEARCH;
        }
        else if(isEnoughHeal)
        {
            log.info("[HURT] Sortie"); 
            currentState = State.IDLE;
        }       
        
        // Action de l'état
        //sayGlobal("POUCE");
    
        // Arrêter de tirer
        if (info.isShooting() || info.isSecondaryShooting()) 
        {
            getAct().act(new StopShooting());
        }
        
        // Rechercher des soins
        Item item = items.getPathNearestSpawnedItem(ItemType.Category.HEALTH);
        if (item == null) 
        {
        	log.info("FUITE");
        	navigate();
        } 
        else 
        {
        	log.info("MEDKIT");
        	navigation.navigate(item);
        	this.item = item;
        }
    }
    
    // -- Pour parler
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
                //log.info("[IDLE] Pas d'objet intéressant trouvé");
                if (navigation.isNavigating()) return;
                
                // On choisit un point aléatoire
                navigation.navigate(navPoints.getRandomNavPoint());
        } 
        else 
        {
                this.item = item;
                //log.info("[IDLE] Je vais prendre --> " + item.getType().getName());
                navigation.navigate(item);        	
        }        
    }    
    
    
    // ---- STATE DEAD 
    // Réprésente notre état DEAD, qui est un état furtif
    @Override
    public void botKilled(BotKilled event) 
    {   
        // Le bot est mort 
        sayGlobal("J'ai été tué.");
        
        // On repasse en IDLE
        log.info("[?] Sortie"); 
        currentState = State.IDLE;
        
        // En le remettant à zéro
        reset();
        
    }   

    ///////////////////////////////////
    public static void main(String args[]) throws PogamutException 
    {
              
    	new UT2004BotRunner(HunterBot.class, "Hunter").setMain(true).setLogLevel(Level.INFO).startAgents(1);
    }
}
