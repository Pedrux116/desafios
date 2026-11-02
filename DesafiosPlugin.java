package br.desafios;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class DesafiosPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final AtomicReference<Challenge> current = new AtomicReference<>();
    private volatile ScheduledTask timeoutTask;
    private ScheduledTask loopTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("desafio") != null) {
            getCommand("desafio").setExecutor(this);
            getCommand("desafio").setTabCompleter(this);
        }
        scheduleLoop();
        getLogger().info("Desafios ativado (Folia).");
    }

    @Override
    public void onDisable() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        current.set(null);
    }

    private void scheduleLoop() {
        if (loopTask != null) loopTask.cancel();
        long period = Math.max(10L, getConfig().getLong("interval-seconds", 300L)) * 20L;
        loopTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, t -> startChallenge(false), period, period);
    }

    // ------------------------------------------------------------------ desafio

    private boolean startChallenge(boolean force) {
        if (current.get() != null) return false;
        if (!force && Bukkit.getOnlinePlayers().size() < getConfig().getInt("min-players", 1)) return false;

        Challenge c = generate();
        if (!current.compareAndSet(null, c)) return false;

        int timeout = Math.max(5, getConfig().getInt("timeout-seconds", 30));
        int reward = getConfig().getInt("reward-amount", 1000);

        timeoutTask = Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> {
            if (current.compareAndSet(c, null)) announceTimeout(c);
        }, timeout * 20L);

        Component title = MM.deserialize("<gold><bold>⚡ NOVO DESAFIO ⚡");
        Component subtitle = Component.text(c.question);
        forEachPlayer(p -> {
            p.sendMessage(Component.empty());
            p.sendMessage(MM.deserialize("<gold><bold>⚡ NOVO DESAFIO ⚡"));
            p.sendMessage(MM.deserialize("<yellow>➤ <white><pergunta>",
                    Placeholder.unparsed("pergunta", c.question)));
            p.sendMessage(MM.deserialize(
                    "<gray>Digite a resposta no chat! Prêmio: <green>$<premio></green> <dark_gray>|</dark_gray> Tempo: <white><tempo>s",
                    Placeholder.unparsed("premio", String.valueOf(reward)),
                    Placeholder.unparsed("tempo", String.valueOf(timeout))));
            p.sendMessage(Component.empty());
            p.showTitle(Title.title(title, subtitle));
            Location l = p.getLocation();
            p.playSound(l, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
            p.playSound(l, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        });
        getLogger().info("Desafio iniciado: " + c.question + " (resposta: " + c.display + ")");
        return true;
    }

    private void announceTimeout(Challenge c) {
        forEachPlayer(p -> {
            p.sendMessage(MM.deserialize("<red><bold>✖ Tempo esgotado!</bold> <gray>Ninguém acertou. A resposta era: <white><resposta>",
                    Placeholder.unparsed("resposta", c.display)));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Challenge c = current.get();
        if (c == null) return;

        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (!c.matches(text)) return;
        if (!current.compareAndSet(c, null)) return; // alguem acertou antes

        ScheduledTask tt = timeoutTask;
        if (tt != null) tt.cancel();

        win(event.getPlayer(), c);
    }

    private void win(Player winner, Challenge c) {
        int reward = getConfig().getInt("reward-amount", 1000);
        double seconds = (System.currentTimeMillis() - c.start) / 1000.0;
        String time = String.format(Locale.ROOT, "%.1f", seconds);

        // Premio: comando executado pelo console (na thread global do Folia)
        String cmd = getConfig().getString("reward-command", "eco give {player} {amount}")
                .replace("{player}", winner.getName())
                .replace("{amount}", String.valueOf(reward));
        Bukkit.getGlobalRegionScheduler().run(this, t -> {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            if (!ok) getLogger().warning("O comando de premio falhou: /" + cmd);
        });
        getLogger().info(winner.getName() + " ganhou o desafio em " + time + "s -> /" + cmd);

        // Mensagem e sons para todos
        forEachPlayer(p -> {
            p.sendMessage(Component.empty());
            p.sendMessage(MM.deserialize(
                    "<gold><bold>★ <jogador></bold> <yellow>acertou <gray>(<white><resposta></white>)</gray> em <green><tempo>s</green> e ganhou <green>$<premio></green>!",
                    Placeholder.unparsed("jogador", winner.getName()),
                    Placeholder.unparsed("resposta", c.display),
                    Placeholder.unparsed("tempo", time),
                    Placeholder.unparsed("premio", String.valueOf(reward))));
            p.sendMessage(Component.empty());
            p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1f, 1f);
        });

        // Efeitos extras no ganhador (na thread dele)
        winner.getScheduler().run(this, t -> {
            winner.showTitle(Title.title(
                    MM.deserialize("<gold><bold>VOCÊ GANHOU!"),
                    MM.deserialize("<green>+$" + reward)));
            Location l = winner.getLocation();
            winner.playSound(l, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            winner.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            winner.playSound(l, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1f);
            winner.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l.clone().add(0, 1, 0), 80, 0.6, 1.0, 0.6, 0.4);
            winner.getWorld().spawnParticle(Particle.FIREWORK, l.clone().add(0, 1, 0), 60, 0.5, 0.8, 0.5, 0.15);
        }, null);

        // Espiral de particulas subindo ao redor do ganhador
        final int[] tick = {0};
        winner.getScheduler().runAtFixedRate(this, task -> {
            if (tick[0]++ >= 30) {
                task.cancel();
                return;
            }
            Location b = winner.getLocation();
            double y = tick[0] * 0.08;
            for (int i = 0; i < 2; i++) {
                double ang = tick[0] * 0.5 + i * Math.PI;
                b.getWorld().spawnParticle(Particle.END_ROD,
                        b.getX() + Math.cos(ang), b.getY() + y, b.getZ() + Math.sin(ang),
                        1, 0, 0, 0, 0);
                b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        b.getX() + Math.cos(ang + 1.5) * 0.7, b.getY() + y, b.getZ() + Math.sin(ang + 1.5) * 0.7,
                        1, 0, 0, 0, 0);
            }
        }, null, 1L, 1L);
    }

    /** Executa a acao para cada jogador na thread da regiao dele (exigencia do Folia). */
    private void forEachPlayer(Consumer<Player> action) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getScheduler().run(this, t -> action.accept(p), null);
        }
    }

    // ------------------------------------------------------------------ comandos

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("desafios.admin")) {
            sender.sendMessage(MM.deserialize("<red>Sem permissão."));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "iniciar" -> {
                if (startChallenge(true)) {
                    sender.sendMessage(MM.deserialize("<green>Desafio iniciado."));
                } else {
                    sender.sendMessage(MM.deserialize("<red>Já existe um desafio ativo."));
                }
            }
            case "cancelar" -> {
                Challenge c = current.getAndSet(null);
                if (c == null) {
                    sender.sendMessage(MM.deserialize("<red>Não há desafio ativo."));
                } else {
                    ScheduledTask tt = timeoutTask;
                    if (tt != null) tt.cancel();
                    forEachPlayer(p -> p.sendMessage(MM.deserialize("<red>O desafio foi cancelado por um administrador.")));
                    sender.sendMessage(MM.deserialize("<green>Desafio cancelado."));
                }
            }
            case "reload" -> {
                reloadConfig();
                scheduleLoop();
                sender.sendMessage(MM.deserialize("<green>Configuração recarregada."));
            }
            default -> sender.sendMessage(MM.deserialize("<yellow>Uso: /desafio <iniciar|cancelar|reload>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("desafios.admin")) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("iniciar", "cancelar", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        return List.of();
    }

    // ------------------------------------------------------------------ perguntas

    private static final class Challenge {
        final String question;
        final String display;
        final Set<String> answers = new HashSet<>();
        final long start = System.currentTimeMillis();

        Challenge(String question, String display, Collection<String> accepted) {
            this.question = question;
            this.display = display;
            answers.add(norm(display));
            for (String a : accepted) answers.add(norm(a));
        }

        boolean matches(String text) {
            return text.length() <= 80 && answers.contains(norm(text));
        }
    }

    private static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        n = n.replace(',', '.')
                .replaceAll("[^a-z0-9.\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        n = n.replaceAll("\\.+$", "");
        if (n.matches("\\d+\\.0+")) n = n.substring(0, n.indexOf('.'));
        return n;
    }

    private static Challenge num(String question, int answer) {
        return new Challenge(question, String.valueOf(answer), List.of(String.valueOf(answer)));
    }

    private static Challenge generate() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        switch (r.nextInt(8)) {
            case 0: {
                int a = r.nextInt(10, 501), b = r.nextInt(10, 501);
                return num("Quanto é " + a + " + " + b + "?", a + b);
            }
            case 1: {
                int a = r.nextInt(50, 801), b = r.nextInt(10, a);
                return num("Quanto é " + a + " - " + b + "?", a - b);
            }
            case 2: {
                int a = r.nextInt(3, 26), b = r.nextInt(3, 26);
                return num("Quanto é " + a + " x " + b + "?", a * b);
            }
            case 3: {
                int b = r.nextInt(2, 13), q = r.nextInt(2, 61);
                return num("Quanto é " + (b * q) + " ÷ " + b + "?", q);
            }
            case 4: {
                int n = r.nextInt(2, 31);
                return num("Qual é a raiz quadrada de " + (n * n) + "?", n);
            }
            default: {
                String[] row = TRIVIA[r.nextInt(TRIVIA.length)];
                return new Challenge(row[0], row[1], Arrays.asList(row).subList(2, row.length));
            }
        }
    }

    // {pergunta, resposta mostrada, respostas aceitas...}
    private static final String[][] TRIVIA = {
            {"Qual é a capital do Brasil?", "Brasília", "brasilia"},
            {"Quantos planetas tem o Sistema Solar?", "8", "8", "oito"},
            {"Qual é o maior planeta do Sistema Solar?", "Júpiter", "jupiter"},
            {"Quantos lados tem um hexágono?", "6", "6", "seis"},
            {"Qual animal é conhecido como o rei da selva?", "Leão", "leao"},
            {"Quantos dias tem um ano bissexto?", "366", "366"},
            {"Qual é o maior oceano do mundo?", "Pacífico", "pacifico", "oceano pacifico"},
            {"Qual elemento químico tem o símbolo O?", "Oxigênio", "oxigenio"},
            {"Quantos jogadores de cada time ficam em campo no futebol?", "11", "11", "onze"},
            {"Quem pintou a Mona Lisa?", "Leonardo da Vinci", "leonardo", "da vinci", "leonardo da vinci"},
            {"Qual planeta é conhecido como o planeta vermelho?", "Marte", "marte"},
            {"Quantos segundos tem um minuto?", "60", "60", "sessenta"},
            {"Qual é a capital do Japão?", "Tóquio", "toquio"},
            {"No Minecraft, qual mob explode perto do jogador?", "Creeper", "creeper"},
            {"No Minecraft, qual bloco é usado para construir o portal do Nether?", "Obsidiana", "obsidiana"},
            {"No Minecraft, quantos corações de vida o jogador tem?", "10", "10", "dez"},
            {"No Minecraft, qual item é usado para domar um lobo?", "Osso", "osso"},
            {"No Minecraft, qual mob dropa pérola do fim?", "Enderman", "enderman"},
            {"Qual é o animal terrestre mais rápido do mundo?", "Guepardo", "guepardo", "chita"},
            {"Quantos ossos tem o corpo de um adulto?", "206", "206"},
            {"Qual gás as plantas absorvem para fazer fotossíntese?", "Gás carbônico", "gas carbonico", "co2", "dioxido de carbono"},
            {"Qual é o maior mamífero do mundo?", "Baleia-azul", "baleia azul"},
            {"Quantas cores tem o arco-íris?", "7", "7", "sete"},
            {"Quem descobriu o Brasil, em 1500?", "Pedro Álvares Cabral", "cabral", "pedro alvares cabral"},
            {"Qual é a capital da Argentina?", "Buenos Aires", "buenos aires"},
            {"Em qual país fica a Torre Eiffel?", "França", "franca"},
            {"Qual é o menor número primo?", "2", "2", "dois"},
            {"H2O é a fórmula química de qual substância?", "Água", "agua"},
            {"Quantos minutos tem uma hora?", "60", "60", "sessenta"},
    };
}
