package com.microsoft.Malmo.MissionHandlers;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;

import com.microsoft.Malmo.Schemas.ContinuousMovementCommand;
import com.microsoft.Malmo.Schemas.ContinuousMovementCommands;
import com.microsoft.Malmo.Schemas.MissionInit;
import com.microsoft.Malmo.Utils.TimeHelper;

/** Class which overrides movement of the Minecraft player and exposes control of it to external agents.<br>
 * This allows the player to act as a robot with the ability to move backwards/forwards, strafe left/right, and turn clockwise/anticlockwise,
 * with a camera that is able to pivot up/down but not turn independently of the agent's body.
 */
public class CommandForWheeledRobotNavigationImplementation extends CommandBase
{
    private boolean overrideKeyboardInput = false;
    private float mVelocity = 0;
    private float mTargetVelocity = 0;
    private int mInertiaTicks = 6;  // Number of ticks it takes to move from current velocity to target velocity.
    private int mTicksSinceLastVelocityChange = 0;
    private float mCameraPitch = 0;
    private float pitchScale = 0;
    private float mYaw = 0;
    private float yawScale = 0;
    private float maxAngularVelocityDegreesPerSecond = 180;
	private long lastAngularUpdateTime;

    private MovementInput overrideMovement = null;
    private MovementInput originalMovement = null;

    public static final String ON_COMMAND_STRING = "1";
    public static final String OFF_COMMAND_STRING = "0";
    
    /** Small MovementInput class that calls our own movement handling code.
     * This object is used by Minecraft to decide how to move the player.
     */
    private class MovementHook extends MovementInputFromOptions
    {
        public MovementHook(GameSettings gameSettingsIn)
        {
            super(gameSettingsIn);
        }
        
        @Override
        public void updatePlayerMoveState()
        {
            if (!CommandForWheeledRobotNavigationImplementation.this.updateState())
            {
                super.updatePlayerMoveState();
            }
        }
    }
    
    public CommandForWheeledRobotNavigationImplementation()
    {
        init();
    }

    private void init()
    {
    	EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
    	this.mVelocity = 0;
        this.mTargetVelocity = 0;
        this.mTicksSinceLastVelocityChange = 0;
        this.mCameraPitch = (player != null) ? player.rotationPitch : 0;
        this.pitchScale = 0;
        this.mYaw = (player != null) ? player.rotationYaw : 0;
        this.yawScale = 0;
    }

    @Override
    public boolean parseParameters(Object params)
    {
    	if (params == null || !(params instanceof ContinuousMovementCommands))
    		return false;
    	
    	ContinuousMovementCommands cmparams = (ContinuousMovementCommands)params;
    	this.maxAngularVelocityDegreesPerSecond = cmparams.getTurnSpeedDegs().floatValue();
    	setUpAllowAndDenyLists(cmparams.getModifierList());
    	return true;
    }
    
    /** Control the number of ticks it takes for the robot to change its momentum.<br>
     * This provides a slightly more realistic feel to the robot's movement.
     * @param ticks number of ticks before momentum change is complete (setting this to 0 means immediate changes).
     */
    public void setInertiaTicks(int ticks)
    {
        mInertiaTicks = ticks;
    }
    
    /** Get the number of ticks of "inertia".
     * @return the number of ticks it takes before a change of speed has taken full effect.
     */
    public int getInertiaTicks()
    {
        return mInertiaTicks;
    }
    
    /** Called by our overridden MovementInputFromOptions class.
     * @return true if we've handled the movement; false if the MovementInputFromOptions class should delegate to the default handling.
     */
    protected boolean updateState()
    {
        if (!overrideKeyboardInput)
        {
            return false;   // Let the class do the default thing.
        }
        // Update movement:
        mTicksSinceLastVelocityChange++;
        if (mTicksSinceLastVelocityChange <= mInertiaTicks)
        {
            mVelocity += (mTargetVelocity - mVelocity) * ((float)mTicksSinceLastVelocityChange/(float)mInertiaTicks);
        }
        else
        {
            mVelocity = mTargetVelocity;
        }
        
        this.overrideMovement.moveForward = mVelocity;

        // This code comes from the Minecraft MovementInput superclass - needed so as not to give the bot an unfair
        // advantage when sneaking!
        if (this.overrideMovement.sneak)
        {
            this.overrideMovement.moveStrafe = (float)((double)this.overrideMovement.moveStrafe * 0.3D);
            this.overrideMovement.moveForward = (float)((double)this.overrideMovement.moveForward * 0.3D);
        }
        updateYawAndPitch();
        return true;
    }

    /** Called to turn the robot / move the camera.
     */
    public void updateYawAndPitch()
    {
    	// Work out the time that has elapsed since we last updated the values.
    	// (We need to do this because we can't guarantee that this method will be
    	// called at a constant frequency.)
    	long timeNow = System.currentTimeMillis();
    	long deltaTime = timeNow - this.lastAngularUpdateTime;
    	this.lastAngularUpdateTime = timeNow;
    	
    	// Work out how much the yaw and pitch should have changed in that time:
    	double overclockScale = 50.0 / (double)TimeHelper.serverTickLength;
    	double deltaYaw = this.yawScale * overclockScale * this.maxAngularVelocityDegreesPerSecond * (deltaTime / 1000.0);
    	double deltaPitch = this.pitchScale * overclockScale * this.maxAngularVelocityDegreesPerSecond * (deltaTime / 1000.0);

    	// And update them:
        mYaw += deltaYaw;
        mCameraPitch -= deltaPitch;
        mCameraPitch = (mCameraPitch < -90) ? -90 : (mCameraPitch > 90 ? 90 : mCameraPitch);    // Clamp to [-90, 90]
    }
    
    @Override
    public boolean isOverriding()
    {
        return overrideKeyboardInput;
    }

    @Override
    public void setOverriding(boolean b)
    {
        init();    // Reset controls back to vanilla state.
        overrideKeyboardInput = b;
    }

    @Override
    public boolean onExecute(String verb, String parameter, MissionInit missionInit)
    {
        if (verb == null || verb.length() == 0)
        {
            return false;
        }
        
        // Now parse the command:
        if (verb.equalsIgnoreCase(ContinuousMovementCommand.MOVE.value()))
        {
            float targetVelocity = clamp(Float.valueOf(parameter));
            if (targetVelocity != mTargetVelocity)
            {
                mTargetVelocity = targetVelocity;
                mTicksSinceLastVelocityChange = 0;
            }
            return true;
        }
        else if (verb.equalsIgnoreCase(ContinuousMovementCommand.STRAFE.value()))
        {
            this.overrideMovement.moveStrafe = -clamp(Float.valueOf(parameter));  // Strafe values need to be reversed for Malmo mod.
            return true;
        }
        else if (verb.equalsIgnoreCase(ContinuousMovementCommand.PITCH.value()))
        {
            this.pitchScale = clamp(Float.valueOf(parameter));
            this.lastAngularUpdateTime = System.currentTimeMillis();
            return true;
        }
        else if (verb.equalsIgnoreCase(ContinuousMovementCommand.TURN.value()))
        {
            this.yawScale = clamp(Float.valueOf(parameter));
            this.lastAngularUpdateTime = System.currentTimeMillis();
            return true;
        }
        else
        {
            // Boolean commands - either on or off.
            boolean value = parameter.equalsIgnoreCase(ON_COMMAND_STRING);
            if (verb.equals(ContinuousMovementCommand.JUMP.value()))
            {
                this.overrideMovement.jump = value;
                return true;
            }
            else if (verb.equalsIgnoreCase(ContinuousMovementCommand.CROUCH.value()))
            {
                this.overrideMovement.sneak = value;
                return true;
            }
        }

        return false;
    }

    private float clamp(float f)
    {
        return (f < -1) ? -1 : ((f > 1) ? 1 : f);
    }

    /** Called for each screen redraw - approximately three times as often as the other tick events, 
     * under normal conditions.<br>
     * This is where we want to update our yaw/pitch, in order to get smooth panning etc
     * (which is how Minecraft itself does it).
     * The speed of the render ticks is not guaranteed, and can vary from machine to machine, so
     * we try to account for this in the calculations.
     * @param ev the RenderTickEvent object for this tick
     */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev)
    {
        if (ev.phase == Phase.START)
        {
            if (this.isOverriding())
            {
                EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
                if (player != null)
                {
                    updateYawAndPitch();
                    player.rotationPitch = this.mCameraPitch;
                    player.rotationYaw = this.mYaw;
                }
            }
        }
    }

    @Override
    public void install(MissionInit missionInit)
    {
        // Create our movement hook, which allows us to override the Minecraft movement.
        this.overrideMovement = new MovementHook(Minecraft.getMinecraft().gameSettings);
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null)
        {
            // Insert it into the player, keeping a record of the original movement object
            // so we can restore it later.
            this.originalMovement = player.movementInput;
            player.movementInput = this.overrideMovement;
        }
        
        FMLCommonHandler.instance().bus().register(this);
    }

    @Override
    public void deinstall(MissionInit missionInit)
    {
        // Restore the player's normal movement control:
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null)
        {
            player.movementInput = this.originalMovement;
        }
        
        FMLCommonHandler.instance().bus().unregister(this);
    }
    
    /** Provide access to the MovementInput object we are using to control the player.<br>
     * This is required by the unit tests.
     * @return our MovementInput object.
     */
    public MovementInput getMover()
    {
        return this.overrideMovement;
    }
    
    /** Get the current player yaw.<br>
     * This is required by the unit tests.
     * @return the yaw of the player.
     */
    public float getCameraYaw()
    {
        return this.mYaw;
    }
    
    /** Get the camera pitch.<br>
     * This is required by the unit tests.
     * @return the pitch. Tra la.
     */
    public float getCameraPitch()
    {
        return this.mCameraPitch;
    }
}