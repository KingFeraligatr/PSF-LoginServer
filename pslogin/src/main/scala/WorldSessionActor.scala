// Copyright (c) 2017 PSForever
import akka.actor.{Actor, ActorRef, Cancellable, MDCContextAware}
import net.psforever.packet.{PlanetSideGamePacket, _}
import net.psforever.packet.control._
import net.psforever.packet.game._
import scodec.Attempt.{Failure, Successful}
import scodec.bits._
import org.log4s.MDC
import MDCContextAware.Implicits._
import net.psforever.packet.game.objectcreate._
import net.psforever.types._

class WorldSessionActor extends Actor with MDCContextAware {
  private[this] val log = org.log4s.getLogger

  private case class PokeClient()

  var sessionId : Long = 0
  var leftRef : ActorRef = ActorRef.noSender
  var rightRef : ActorRef = ActorRef.noSender

  var clientKeepAlive : Cancellable = null

  override def postStop() = {
    if(clientKeepAlive != null)
      clientKeepAlive.cancel()
  }

  def receive = Initializing

  def Initializing : Receive = {
    case HelloFriend(inSessionId, right) =>
      this.sessionId = inSessionId
      leftRef = sender()
      rightRef = right.asInstanceOf[ActorRef]

      context.become(Started)
    case _ =>
      log.error("Unknown message")
      context.stop(self)
  }

  def Started : Receive = {
    case ctrl @ ControlPacket(_, _) =>
      handlePktContainer(ctrl)
    case game @ GamePacket(_, _, _) =>
      handlePktContainer(game)
      // temporary hack to keep the client from disconnecting
    case PokeClient() =>
      sendResponse(PacketCoding.CreateGamePacket(0, KeepAliveMessage(0)))
    case default => failWithError(s"Invalid packet class received: $default")
  }

  def handlePkt(pkt : PlanetSidePacket) : Unit = pkt match {
    case ctrl : PlanetSideControlPacket =>
      handleControlPkt(ctrl)
    case game : PlanetSideGamePacket =>
      handleGamePkt(game)
    case default => failWithError(s"Invalid packet class received: $default")
  }

  def handlePktContainer(pkt : PlanetSidePacketContainer) : Unit = pkt match {
    case ctrl @ ControlPacket(opcode, ctrlPkt) =>
      handleControlPkt(ctrlPkt)
    case game @ GamePacket(opcode, seq, gamePkt) =>
      handleGamePkt(gamePkt)
    case default => failWithError(s"Invalid packet container class received: $default")
  }

  def handleControlPkt(pkt : PlanetSideControlPacket) = {
    pkt match {
      case SlottedMetaPacket(slot, subslot, innerPacket) =>
        sendResponse(PacketCoding.CreateControlPacket(SlottedMetaAck(slot, subslot)))

        PacketCoding.DecodePacket(innerPacket) match {
          case Failure(e) =>
            log.error(s"Failed to decode inner packet of SlottedMetaPacket: $e")
          case Successful(v) =>
            handlePkt(v)
        }
      case sync @ ControlSync(diff, unk, f1, f2, f3, f4, fa, fb) =>
        log.debug(s"SYNC: $sync")
        val serverTick = Math.abs(System.nanoTime().toInt) // limit the size to prevent encoding error
        sendResponse(PacketCoding.CreateControlPacket(ControlSyncResp(diff, serverTick,
          fa, fb, fb, fa)))
      case MultiPacket(packets) =>
        packets.foreach { pkt =>
          PacketCoding.DecodePacket(pkt) match {
            case Failure(e) =>
              log.error(s"Failed to decode inner packet of MultiPacket: $e")
            case Successful(v) =>
              handlePkt(v)
          }
        }
      case MultiPacketEx(packets) =>
        packets.foreach { pkt =>
          PacketCoding.DecodePacket(pkt) match {
            case Failure(e) =>
              log.error(s"Failed to decode inner packet of MultiPacketEx: $e")
            case Successful(v) =>
              handlePkt(v)
          }
        }
      case default =>
        log.debug(s"Unhandled ControlPacket $default")
    }
  }

  //val objectHex = hex"18 57 0C 00 00 BC 84 B0 06 C2 D7 65 53 5C A1 60 00 01 34 40 00 09 70 49 00 6C 00 6C 00 6C 00 49 00 49 00 49 00 6C 00 6C 00 6C 00 49 00 6C 00 49 00 6C 00 6C 00 49 00 6C 00 6C 00 6C 00 49 00 6C 00 6C 00 49 00 84 52 70 76 1E 80 80 00 00 00 00 00 3F FF C0 00 00 00 20 00 00 0F F6 A7 03 FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FD 90 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 90 01 90 00 64 00 00 01 00 7E C8 00 C8 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 C0 00 42 C5 46 86 C7 00 00 00 80 00 00 12 40 78 70 65 5F 73 61 6E 63 74 75 61 72 79 5F 68 65 6C 70 90 78 70 65 5F 74 68 5F 66 69 72 65 6D 6F 64 65 73 8B 75 73 65 64 5F 62 65 61 6D 65 72 85 6D 61 70 31 33 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 0A 23 02 60 04 04 40 00 00 10 00 06 02 08 14 D0 08 0C 80 00 02 00 02 6B 4E 00 82 88 00 00 02 00 00 C0 41 C0 9E 01 01 90 00 00 64 00 44 2A 00 10 91 00 00 00 40 00 18 08 38 94 40 20 32 00 00 00 80 19 05 48 02 17 20 00 00 08 00 70 29 80 43 64 00 00 32 00 0E 05 40 08 9C 80 00 06 40 01 C0 AA 01 19 90 00 00 C8 00 3A 15 80 28 72 00 00 19 00 04 0A B8 05 26 40 00 03 20 06 C2 58 00 A7 88 00 00 02 00 00 80 00 00"
  //currently, the character's starting BEP is discarded due to unknown bit format
  val app = CharacterAppearanceData(
    PlacementData(
      Vector3(3674.8438f, 2726.789f, 91.15625f),
      Vector3(0f, 0f, 90f)
    ),
    BasicCharacterData(
      "IlllIIIlllIlIllIlllIllI",
      PlanetSideEmpire.VS,
      CharacterGender.Female,
      41,
      1
    ),
    3,
    false,
    false,
    ExoSuitType.Standard,
    "",
    0,
    false,
    0, 181,
    true,
    GrenadeState.None,
    false,
    false,
    false,
    RibbonBars()
  )
  val inv = InventoryItem(ObjectClass.beamer, PlanetSideGUID(76), 0, DetailedWeaponData(4, 8, ObjectClass.energy_cell, PlanetSideGUID(77), 0, DetailedAmmoBoxData(8, 16))) ::
    InventoryItem(ObjectClass.suppressor, PlanetSideGUID(78), 2, DetailedWeaponData(4, 8, ObjectClass.bullet_9mm, PlanetSideGUID(79), 0, DetailedAmmoBoxData(8, 25))) ::
    InventoryItem(ObjectClass.forceblade, PlanetSideGUID(80), 4, DetailedWeaponData(4, 8, ObjectClass.melee_ammo, PlanetSideGUID(81), 0, DetailedAmmoBoxData(8, 1))) ::
    InventoryItem(ObjectClass.locker_container, PlanetSideGUID(82), 5, DetailedAmmoBoxData(8, 1)) ::
    InventoryItem(ObjectClass.bullet_9mm, PlanetSideGUID(83), 6, DetailedAmmoBoxData(8, 50)) ::
    InventoryItem(ObjectClass.bullet_9mm, PlanetSideGUID(84), 9, DetailedAmmoBoxData(8, 50)) ::
    InventoryItem(ObjectClass.bullet_9mm, PlanetSideGUID(85), 12, DetailedAmmoBoxData(8, 50)) ::
    InventoryItem(ObjectClass.bullet_9mm_AP, PlanetSideGUID(86), 33, DetailedAmmoBoxData(8, 50)) ::
    InventoryItem(ObjectClass.energy_cell, PlanetSideGUID(87), 36, DetailedAmmoBoxData(8, 50)) ::
    InventoryItem(ObjectClass.remote_electronics_kit, PlanetSideGUID(88), 39, DetailedREKData(8)) ::
    Nil
  val obj = DetailedCharacterData(
    app,
    100, 100,
    50,
    1, 7, 7,
    100, 100,
    28, 4, 44, 84, 104, 1900,
    "xpe_sanctuary_help" :: "xpe_th_firemodes" :: "used_beamer" :: "map13" :: Nil,
    List.empty,
    InventoryData(inv),
    DrawnSlot.None
  )
  val objectHex = ObjectCreateDetailedMessage(ObjectClass.avatar, PlanetSideGUID(75), obj)

  def handleGamePkt(pkt : PlanetSideGamePacket) = pkt match {
    case ConnectToWorldRequestMessage(server, token, majorVersion, minorVersion, revision, buildDate, unk) =>

      val clientVersion = s"Client Version: $majorVersion.$minorVersion.$revision, $buildDate"

      log.info(s"New world login to $server with Token:$token. $clientVersion")

      // ObjectCreateMessage
      sendResponse(PacketCoding.CreateGamePacket(0, objectHex))
      // XXX: hard coded message
      sendRawResponse(hex"14 0F 00 00 00 10 27 00  00 C1 D8 7A 02 4B 00 26 5C B0 80 00 ")

      // NOTE: PlanetSideZoneID just chooses the background
      sendResponse(PacketCoding.CreateGamePacket(0,
        CharacterInfoMessage(0, PlanetSideZoneID(1), 0, PlanetSideGUID(0), true, 0)))
    case msg @ CharacterRequestMessage(charId, action) =>
      log.info("Handling " + msg)

      action match {
        case CharacterRequestAction.Delete =>
          sendResponse(PacketCoding.CreateGamePacket(0, ActionResultMessage(false, Some(1))))
        case CharacterRequestAction.Select =>
          objectHex match {
            case obj @ ObjectCreateDetailedMessage(len, cls, guid, _, _) =>
              log.debug("Object: " + obj)
              // LoadMapMessage 13714 in mossy .gcap
              // XXX: hardcoded shit
              sendResponse(PacketCoding.CreateGamePacket(0, LoadMapMessage("map13","home3",40100,25,true,3770441820L))) //VS Sanctuary
              sendResponse(PacketCoding.CreateGamePacket(0, ZonePopulationUpdateMessage(PlanetSideGUID(13), 414, 138, 0, 138, 0, 138, 0, 138, 0)))
              sendResponse(PacketCoding.CreateGamePacket(0, objectHex))

              // These object_guids are specfic to VS Sanc
              sendResponse(PacketCoding.CreateGamePacket(0, SetEmpireMessage(PlanetSideGUID(2), PlanetSideEmpire.VS))) //HART building C
              sendResponse(PacketCoding.CreateGamePacket(0, SetEmpireMessage(PlanetSideGUID(29), PlanetSideEmpire.NC))) //South Villa Gun Tower

              sendResponse(PacketCoding.CreateGamePacket(0, TimeOfDayMessage(1191182336)))
              sendResponse(PacketCoding.CreateGamePacket(0, ContinentalLockUpdateMessage(PlanetSideGUID(13), PlanetSideEmpire.VS))) // "The VS have captured the VS Sanctuary."
              sendResponse(PacketCoding.CreateGamePacket(0, BroadcastWarpgateUpdateMessage(PlanetSideGUID(13), PlanetSideGUID(1), false, false, true))) // VS Sanctuary: Inactive Warpgate -> Broadcast Warpgate

              sendResponse(PacketCoding.CreateGamePacket(0,BuildingInfoUpdateMessage(
                PlanetSideGUID(6),   //Ceryshen
                PlanetSideGUID(2),   //Anguta
                8,                   //80% NTU
                true,                //Base hacked
                PlanetSideEmpire.NC, //Base hacked by NC
                600000,              //10 minutes remaining for hack
                PlanetSideEmpire.VS, //Base owned by VS
                0,                   //!! Field != 0 will cause malformed packet. See class def.
                None,
                PlanetSideGeneratorState.Critical, //Generator critical
                true,                //Respawn tubes destroyed
                true,                //Force dome active
                16,                  //Tech plant lattice benefit
                0,
                Nil,                   //!! Field > 0 will cause malformed packet. See class def.
                0,
                false,
                8,                   //!! Field != 8 will cause malformed packet. See class def.
                None,
                true,                //Boosted spawn room pain field
                true)))              //Boosted generator room pain field

              sendResponse(PacketCoding.CreateGamePacket(0, SetCurrentAvatarMessage(guid,0,0)))
              sendResponse(PacketCoding.CreateGamePacket(0, CreateShortcutMessage(guid, 1, 0, true, Shortcut.MEDKIT)))
              sendResponse(PacketCoding.CreateGamePacket(0, ReplicationStreamMessage(5, Some(6), Vector(SquadListing())))) //clear squad list

              val fury = VehicleData(
                CommonFieldData(
                  PlacementData(3674.8438f, 2732f, 91.15625f, 0.0f, 0.0f, 90.0f),
                  PlanetSideEmpire.VS, 4
                ),
                255,
                MountItem(ObjectClass.fury_weapon_systema, PlanetSideGUID(400), 1,
                  WeaponData(0x6, 0x8, 0, ObjectClass.hellfire_ammo, PlanetSideGUID(432), 0, AmmoBoxData(0x8))
                )
              )
              sendResponse(PacketCoding.CreateGamePacket(0, ObjectCreateMessage(ObjectClass.fury, PlanetSideGUID(413), fury)))

              import scala.concurrent.duration._
              import scala.concurrent.ExecutionContext.Implicits.global
              clientKeepAlive = context.system.scheduler.schedule(0 seconds, 500 milliseconds, self, PokeClient())
          }
        case default =>
          log.error("Unsupported " + default + " in " + msg)
      }
    case msg @ CharacterCreateRequestMessage(name, head, voice, gender, empire) =>
      log.info("Handling " + msg)

      sendResponse(PacketCoding.CreateGamePacket(0, ActionResultMessage(true, None)))
      sendResponse(PacketCoding.CreateGamePacket(0,
        CharacterInfoMessage(0, PlanetSideZoneID(0), 0, PlanetSideGUID(0), true, 0)))

    case KeepAliveMessage(code) =>
      sendResponse(PacketCoding.CreateGamePacket(0, KeepAliveMessage()))

    case msg @ BeginZoningMessage() =>
      log.info("Reticulating splines ...")

    case msg @ PlayerStateMessageUpstream(avatar_guid, pos, vel, yaw, pitch, yawUpper, seq_time, unk3, is_crouching, is_jumping, unk4, is_cloaking, unk5, unk6) =>
      //log.info("PlayerState: " + msg)

    case msg @ ChildObjectStateMessage(object_guid, pitch, yaw) =>
      //log.info("ChildObjectState: " + msg)

    case msg @ VehicleStateMessage(vehicle_guid, unk1, pos, ang, vel, unk5, unk6, unk7, wheels, unk9, unkA) =>
      //log.info("VehicleState: " + msg)

    case msg @ ProjectileStateMessage(projectile_guid, shot_pos, shot_vector, unk1, unk2, unk3, unk4, time_alive) =>
      //log.info("ProjectileState: " + msg)

    case msg @ ChatMsg(messagetype, has_wide_contents, recipient, contents, note_contents) =>
      // TODO: Prevents log spam, but should be handled correctly
      if (messagetype != ChatMessageType.CMT_TOGGLE_GM) {
        log.info("Chat: " + msg)
      }

      if (messagetype == ChatMessageType.CMT_VOICE) {
        sendResponse(PacketCoding.CreateGamePacket(0, ChatMsg(ChatMessageType.CMT_VOICE, false, "IlllIIIlllIlIllIlllIllI", contents, None)))
      }

      // TODO: handle this appropriately
      if(messagetype == ChatMessageType.CMT_QUIT) {
        sendResponse(DropCryptoSession())
        sendResponse(DropSession(sessionId, "user quit"))
      }

      // TODO: Depending on messagetype, may need to prepend sender's name to contents with proper spacing
      // TODO: Just replays the packet straight back to sender; actually needs to be routed to recipients!
      sendResponse(PacketCoding.CreateGamePacket(0, ChatMsg(messagetype, has_wide_contents, recipient, contents, note_contents)))

    case msg @ VoiceHostRequest(unk, PlanetSideGUID(player_guid), data) =>
      log.info("Player "+player_guid+" requested in-game voice chat.")
      sendResponse(PacketCoding.CreateGamePacket(0, VoiceHostKill()))

    case msg @ VoiceHostInfo(player_guid, data) =>
      sendResponse(PacketCoding.CreateGamePacket(0, VoiceHostKill()))

    case msg @ ChangeFireModeMessage(item_guid, fire_mode) =>
      log.info("ChangeFireMode: " + msg)

    case msg @ ChangeFireStateMessage_Start(item_guid) =>
      log.info("ChangeFireState_Start: " + msg)

    case msg @ ChangeFireStateMessage_Stop(item_guid) =>
      log.info("ChangeFireState_Stop: " + msg)

    case msg @ EmoteMsg(avatar_guid, emote) =>
      log.info("Emote: " + msg)
      sendResponse(PacketCoding.CreateGamePacket(0, EmoteMsg(avatar_guid, emote)))

    case msg @ DropItemMessage(item_guid) =>
      log.info("DropItem: " + msg)
      //item dropped where you spawn in VS Sanctuary
      sendResponse(PacketCoding.CreateGamePacket(0, ObjectDetachMessage(PlanetSideGUID(75), item_guid, app.pos.coord, 0, 0, 0)))

    case msg @ PickupItemMessage(item_guid, player_guid, unk1, unk2) =>
      log.info("PickupItem: " + msg)
      sendResponse(PacketCoding.CreateGamePacket(0, PickupItemMessage(item_guid, player_guid, unk1, unk2)))
      sendResponse(PacketCoding.CreateGamePacket(0, ObjectAttachMessage(player_guid, item_guid, 250))) // item on mouse

    case msg @ ReloadMessage(item_guid, ammo_clip, unk1) =>
      log.info("Reload: " + msg)
      sendResponse(PacketCoding.CreateGamePacket(0, ReloadMessage(item_guid, 123, unk1)))

    case msg @ ObjectHeldMessage(avatar_guid, held_holsters, unk1) =>
      log.info("ObjectHeld: " + msg)

    case msg @ AvatarJumpMessage(state) =>
      //log.info("AvatarJump: " + msg)

    case msg @ ZipLineMessage(player_guid,origin_side,action,id,pos) =>
      log.info("ZipLineMessage: " + msg)
      if (!origin_side && action == 0) {
        //doing this lets you use the zip line in one direction, cant come back
        sendResponse(PacketCoding.CreateGamePacket(0, ZipLineMessage(player_guid, origin_side, action, id, pos)))
      }
      else if (!origin_side && action == 1) {
        //disembark from zipline at destination !
        sendResponse(PacketCoding.CreateGamePacket(0, ZipLineMessage(player_guid, origin_side, action, 0, pos)))
      }
      else if (!origin_side && action == 2) {
        //get off by force
        sendResponse(PacketCoding.CreateGamePacket(0, ZipLineMessage(player_guid, origin_side, action, 0, pos)))
      }
      else if (origin_side && action == 0) {
        // for teleporters & the other zipline direction
      }

    case msg @ RequestDestroyMessage(object_guid) =>
      log.info("RequestDestroy: " + msg)
      // TODO: Make sure this is the correct response in all cases
      sendResponse(PacketCoding.CreateGamePacket(0, ObjectDeleteMessage(object_guid, 0)))

    case msg @ ObjectDeleteMessage(object_guid, unk1) =>
      sendResponse(PacketCoding.CreateGamePacket(0, ObjectDeleteMessage(object_guid, 0)))
      log.info("ObjectDelete: " + msg)

    case msg @ MoveItemMessage(item_guid, avatar_guid_1, avatar_guid_2, dest, unk1) =>
      sendResponse(PacketCoding.CreateGamePacket(0, ObjectAttachMessage(avatar_guid_1,item_guid,dest)))
      log.info("MoveItem: " + msg)

    case msg @ ChangeAmmoMessage(item_guid, unk1) =>
      log.info("ChangeAmmo: " + msg)

    case msg @ UseItemMessage(avatar_guid, unk1, object_guid, unk2, unk3, unk4, unk5, unk6, unk7, unk8, itemType) =>
      log.info("UseItem: " + msg)
      // TODO: Not all fields in the response are identical to source in real packet logs (but seems to be ok)
      // TODO: Not all incoming UseItemMessage's respond with another UseItemMessage (i.e. doors only send out GenericObjectStateMsg)
      if (itemType != 121) sendResponse(PacketCoding.CreateGamePacket(0, UseItemMessage(avatar_guid, unk1, object_guid, unk2, unk3, unk4, unk5, unk6, unk7, unk8, itemType)))
      if (itemType == 121 && !unk3){ // TODO : medkit use ?!
        sendResponse(PacketCoding.CreateGamePacket(0, UseItemMessage(avatar_guid, unk1, object_guid, 0, unk3, unk4, unk5, unk6, unk7, unk8, itemType)))
        sendResponse(PacketCoding.CreateGamePacket(0, PlanetsideAttributeMessage(avatar_guid, 0, 100))) // avatar with 100 hp
        sendResponse(PacketCoding.CreateGamePacket(0, ObjectDeleteMessage(PlanetSideGUID(unk1), 2)))
      }
      if (unk1 == 0 && !unk3 && unk7 == 25) {
        // TODO: This should only actually be sent to doors upon opening; may break non-door items upon use
        sendResponse(PacketCoding.CreateGamePacket(0, GenericObjectStateMsg(object_guid, 16)))
      }
    
    case msg @ UnuseItemMessage(player, item) =>
      log.info("UnuseItem: " + msg)

    case msg @ DeployObjectMessage(guid, unk1, pos, roll, pitch, yaw, unk2) =>
      log.info("DeployObject: " + msg)

    case msg @ GenericObjectStateMsg(object_guid, unk1) =>
      log.info("GenericObjectState: " + msg)

    case msg @ ItemTransactionMessage(terminal_guid, transaction_type, item_page, item_name, unk1, item_guid) =>
      if(transaction_type == TransactionType.Sell) {
        sendResponse(PacketCoding.CreateGamePacket(0, ObjectDeleteMessage(item_guid, 0)))
        sendResponse(PacketCoding.CreateGamePacket(0, ItemTransactionResultMessage(terminal_guid, transaction_type, true)))
      }
      log.info("ItemTransaction: " + msg)

    case msg @ WeaponDelayFireMessage(seq_time, weapon_guid) =>
      log.info("WeaponDelayFire: " + msg)

    case msg @ WeaponFireMessage(seq_time, weapon_guid, projectile_guid, shot_origin, unk1, unk2, unk3, unk4, unk5, unk6, unk7) =>
      log.info("WeaponFire: " + msg)

    case msg @ WeaponLazeTargetPositionMessage(weapon, pos1, pos2) =>
      log.info("Lazing position: " + pos2.toString)

    case msg @ HitMessage(seq_time, projectile_guid, unk1, hit_info, unk2, unk3, unk4) =>
      log.info("Hit: " + msg)

    case msg @ SplashHitMessage(unk1, unk2, unk3, unk4, unk5, unk6, unk7, unk8) =>
      log.info("SplashHitMessage: " + msg)

    case msg @ AvatarFirstTimeEventMessage(avatar_guid, object_guid, unk1, event_name) =>
      log.info("AvatarFirstTimeEvent: " + msg)

    case msg @ WarpgateRequest(continent_guid, building_guid, dest_building_guid, dest_continent_guid, unk1, unk2) =>
      log.info("WarpgateRequest: " + msg)

    case msg @ MountVehicleMsg(player_guid, vehicle_guid, unk) =>
      sendResponse(PacketCoding.CreateGamePacket(0, ObjectAttachMessage(vehicle_guid,player_guid,0)))
      log.info("MounVehicleMsg: "+msg)

    case msg @ DismountVehicleMsg(player_guid, unk1, unk2) =>
      sendResponse(PacketCoding.CreateGamePacket(0, msg)) //should be safe; replace with ObjectDetachMessage later
      log.info("DismountVehicleMsg: " + msg)

    case msg @ DeployRequestMessage(player, entity, unk1, unk2, unk3, pos) =>
      //if you try to deploy, can not undeploy
      log.info("DeployRequest: " + msg)

    case msg @ AvatarGrenadeStateMessage(player_guid, state) =>
      log.info("AvatarGrenadeStateMessage: " + msg)

    case msg @ SquadDefinitionActionMessage(a, b, c, d, e, f, g, h, i) =>
      log.info("SquadDefinitionAction: " + msg)

    case msg @ GenericCollisionMsg(u1, p, t, php, thp, pv, tv, ppos, tpos, u2, u3, u4) =>
      log.info("Ouch! " + msg)

    case msg @ BugReportMessage(version_major,version_minor,version_date,bug_type,repeatable,location,zone,pos,summary,desc) =>
      log.info("BugReportMessage: " + msg)

    case msg @ BindPlayerMessage(action, bindDesc, unk1, logging, unk2, unk3, unk4, pos) =>
      log.info("BindPlayerMessage: " + msg)

    case msg @ PlanetsideAttributeMessage(avatar_guid, attribute_type, attribute_value) =>
      log.info("PlanetsideAttributeMessage: "+msg)
      sendResponse(PacketCoding.CreateGamePacket(0,PlanetsideAttributeMessage(avatar_guid, attribute_type, attribute_value)))

    case msg @ BattleplanMessage(char_id, player_name, zonr_id, diagrams) =>
      log.info("Battleplan: "+msg)

    case msg @ CreateShortcutMessage(player_guid, slot, unk, add, shortcut) =>
      log.info("CreateShortcutMessage: "+msg)

    case msg @ FriendsRequest(action, friend) =>
      log.info("FriendsRequest: "+msg)

    case msg @ HitHint(source, player) =>
      log.info("HitHint: "+msg)

    case msg @ WeaponDryFireMessage(weapon) =>
      log.info("WeaponDryFireMessage: "+msg)

    case msg @ TargetingImplantRequest(list) =>
      log.info("TargetingImplantRequest: "+msg)

    case default => log.error(s"Unhandled GamePacket $pkt")
  }

  def failWithError(error : String) = {
    log.error(error)
    //sendResponse(PacketCoding.CreateControlPacket(ConnectionClose()))
  }

  def sendResponse(cont : PlanetSidePacketContainer) : Unit = {
    log.trace("WORLD SEND: " + cont)
    sendResponse(cont.asInstanceOf[Any])
  }

  def sendResponse(msg : Any) : Unit = {
    MDC("sessionId") = sessionId.toString
    rightRef !> msg
  }

  def sendRawResponse(pkt : ByteVector) = {
    log.trace("WORLD SEND RAW: " + pkt)
    sendResponse(RawPacket(pkt))
  }
}
